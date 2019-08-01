package com.siberika.idea.pascal.debugger;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.siberika.idea.pascal.debugger.gdb.GdbExecutionStack;
import com.siberika.idea.pascal.debugger.gdb.GdbStackFrame;
import com.siberika.idea.pascal.debugger.gdb.GdbVariableObject;
import com.siberika.idea.pascal.debugger.gdb.parser.GdbMiLine;
import com.siberika.idea.pascal.debugger.gdb.parser.GdbMiResults;
import com.siberika.idea.pascal.lang.parser.NamespaceRec;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.references.PasReferenceUtil;
import com.siberika.idea.pascal.lang.references.ResolveContext;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

public class VariableManager {

    private static final Logger LOG = Logger.getInstance(VariableManager.class);

    private static final String VAR_PREFIX_LOCAL = "l%";
    private static final String VAR_PREFIX_WATCHES = "w%";
    private static final String OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX = "high";
    private static final List<String> TYPES_STRING = Arrays.asList("ANSISTRING", "WIDESTRING", "UNICODESTRING", "UTF8STRING", "RAWBYTESTRING");
    private static final Map<Long, String> CODEPAGE_MAP = createCodePageMap();
    private static final CommandSender.FinishCallback SILENT = res -> {};

    private static Map<Long, String> createCodePageMap() {
        HashMap<Long, String> res = new HashMap<>();
        res.put(0L, "ACP");
        res.put(1L, "OEMCP");
        res.put(1200L, "UTF16");
        res.put(1201L, "UTF16BE");
        res.put(65000L, "UTF7");
        res.put(65001L, "UTF8");
        res.put(20127L, "ASCII");
        res.put(65535L, "NONE");
        return res;
    }

    private final PascalXDebugProcess process;

    private Map<String, GdbVariableObject> variableObjectMap;
    private final Map<String, Collection<PasField>> fieldsMap = new ConcurrentHashMap<>();

    VariableManager(PascalXDebugProcess process) {
        this.process = process;
        this.variableObjectMap = new LinkedHashMap<>();
    }

    public void queryVariables(int level, GdbExecutionStack executionStack, GdbStackFrame frame) {
        process.sendCommand("-stack-select-frame " + level);
        if (process.options.supportsBulkDelete) {
            process.sendCommand("-var-delete *");
        }
        variableObjectMap.clear();
        process.sendCommand(String.format("-stack-list-variables --thread %s --frame %d --no-values", executionStack.getThreadId(), level),
                new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        if (res.getResults().getValue("variables") != null) {
                            handleVariablesResponse(frame, res.getResults().getList("variables"));
                        } else {
                            LOG.info(String.format("DBG Error: Invalid debugger response for variables: %s", res.toString()));
                        }
                    }
                });
        process.syncCalls(4, res -> frame.refreshVarTree(variableObjectMap.values()));
    }

    // handling of -stack-list-variables command
    private void handleVariablesResponse(GdbStackFrame frame, List<Object> variables) {
        for (Object o : variables) {
            if (o instanceof GdbMiResults) {
                GdbMiResults res = (GdbMiResults) o;
                String varName = res.getString("name");
                final String varKey = getVarKey(varName, false, VAR_PREFIX_LOCAL);
                if (!process.options.supportsBulkDelete) {
                    process.sendCommand("-var-delete " + varKey, SILENT);
                }
                process.sendCommand(String.format("-var-create %4$s%s%4$s %s \"%s\"", varKey, process.getVarFrame(), varName, process.getVarNameQuoteChar()));
                GdbVariableObject var = new GdbVariableObject(frame, varKey, varName, varName, null, res);
                variableObjectMap.put(varKey, var);
                resolveVariable(var);
            } else {
                LOG.error(String.format("DBG Error: Invalid variables list entry: %s", o));
            }
        }
    }

    // handling of -var-create command
    void handleVarResult(GdbMiResults res) {
        handleVarData(null, res);
    }

    private void handleVarData(GdbVariableObject parent, GdbMiResults res) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            String varName = res.getString("name");
            String varKey;
            if (varName.startsWith(VAR_PREFIX_LOCAL) || varName.startsWith(VAR_PREFIX_WATCHES)) {
                varKey = varName;
            } else {
                LOG.info("=== DBG Error: name w/o prefix: " + varName);
                varKey = getVarKey(varName, false, VAR_PREFIX_LOCAL);
            }
            GdbVariableObject var;
            if (parent != null) {
                String id = varName.substring(varName.lastIndexOf('.') + 1);
                var = parent.findChild(id);
                if (var != null) {
                    LOG.info("=== DBG Error: child var already exists: " + varName);
                } else {
                    var = new GdbVariableObject((GdbStackFrame) frame, varKey, id, parent.getExpression() + "." + id, null);
                    parent.getChildren().add(var);
                }
            } else {
                var = variableObjectMap.get(varKey);
            }
            if (var != null) {
                var.updateFromResult(res);
                refineOpenArray(var, res);
                refineDynamicArray(var, res);
                refineString(var, res);
                updateVariableObjectUI(var);
            } else {
                LOG.info("DBG Error: variable not found: " + varKey);
            }
        }
    }

    // handling of -var-update command
    void handleVarUpdate(GdbMiResults results) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            List<Object> changes = results.getList("changelist");
            for (Object o : changes) {
                GdbMiResults change = (GdbMiResults) o;
            }
        }
    }

    private void resolveVariable(GdbVariableObject var) {
        String varNameResolved = var.getName();
        PasField.FieldType fieldType = PasField.FieldType.VARIABLE;
        boolean hidden = isHidden(var.getName());
        if (!hidden) {
            PasField field = resolveIdentifierName(process.getCurrentFrame().getSourcePosition(), var.getName(), PasField.TYPES_LOCAL);
            if (field != null) {
                varNameResolved = formatVariableName(field);
                fieldType = field.fieldType;
            }
        }
        var.setExpression(varNameResolved);
        var.setFieldType(fieldType);
        var.setVisible(!hidden);
    }

    void removeVariable(String varKey) {
        XStackFrame frame = process.getCurrentFrame();
        if (frame instanceof GdbStackFrame) {
            variableObjectMap.remove(varKey);
        }
    }

    void computeValueChildren(String name, XCompositeNode node) {
        GdbVariableObject tempParent = findVarObject(name);
        if (tempParent != null) {
            process.sendCommand("-var-list-children --all-values " + name + " 0 " + process.options.limitChilds, new CommandSender.FinishCallback() {
                @Override
                public void call(GdbMiLine res) {
                    if ("0".equals(res.getResults().getString("numchild"))) {
                        tempParent.getFrame().refreshVarTree(node, Collections.emptyList());
                        return;
                    }
                    final List<Object> children = res.getResults() != null ? res.getResults().getList("children") : null;
                    if (children != null) {
                        for (Object variable : children) {
                            if (variable instanceof GdbMiResults) {
                                final GdbMiResults child = ((GdbMiResults) variable).getTuple("child");
                                if (child != null) {
                                    handleVarData(tempParent, child);
                                } else {
                                    LOG.info("DBG Error: invalid chldren entry: " + res);
                                }
                            }
                        }
                        process.syncCalls(3, new CommandSender.FinishCallback() {
                            @Override
                            public void call(GdbMiLine res) {
                                tempParent.getFrame().refreshVarTree(node, tempParent.getChildren());
                            }
                        });
                    }
                }
            });
        }
    }

    private GdbVariableObject findVarObject(String name) {
        GdbVariableObject res = null;
        String[] nameList = name.split("\\.", 100);
        for (String level : nameList) {
            if (res != null) {
                res = res.findChild(level);
            } else {
                res = variableObjectMap.get(level);
            }
            if (null == res) {
                LOG.info(String.format("DBG Error: variable level %s not found in hierarchy %s", level, name));
            }
        }
        return res;
    }

    private void refineOpenArray(GdbVariableObject highBoundVar, GdbMiResults res) {
        if (!process.options.refineOpenArrays) {
            return;
        }
        if (highBoundVar.getName().startsWith(OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX)) {
            Integer highIndex = res.getInteger("value");
            if (highIndex != null) {
                final String openArrayName = highBoundVar.getName().substring(4);
                GdbVariableObject openArrayVar = variableObjectMap.get(getVarKey(openArrayName, false, VAR_PREFIX_LOCAL));
                if (openArrayVar != null) {
                    openArrayVar.setChildrenCount(0);
                    openArrayVar.setLength(highIndex + 1);
                    if (process.options.supportsSummary) {
                        long displayLength = Math.min(openArrayVar.getLength(), process.options.limitElements);
                        openArrayVar.setAdditional(Long.toString(openArrayVar.getLength()));
                        process.sendCommand(String.format("type summary add -s \"%s\\$${var[0-%d]}\" -n %s", openArrayVar.getKey(), displayLength-1, openArrayVar.getKey()));
                        process.sendCommand(String.format("fr v %s --summary %s", openArrayName, openArrayVar.getKey()));
                    }
                } else {
                    LOG.info(String.format("DBG Error: no array variable found for bound param %s", highBoundVar.getName()));
                }
            }
        }
    }

    private void refineDynamicArray(GdbVariableObject var, GdbMiResults res) {
        if (!process.options.refineDynamicArrays) {
            return;
        }
        String type = res.getString("type");
        if (isDynamicArray(type)) {
            String addressStr = res.getString("value");
            if (addressStr != null) {
                if (!isValidAddress(addressStr)) {
                    return;
                }
                int size = addressStr.length() > 8 ? 8 : 4;
                process.sendCommand(String.format("-data-read-memory-bytes %s-%d %d", addressStr, size * 2, size * 2), new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        List<Object> memory = res.getResults().getValue("memory") != null ? res.getResults().getList("memory") : null;
                        GdbMiResults tuple = ((memory != null) && (memory.size() > 0)) ? (GdbMiResults) memory.get(0) : null;
                        String content = tuple != null ? tuple.getString("contents") : null;
                        if ((content != null) && (content.length() == (size * 2 * 2))) {
                            long refCount = parseHex(content.substring(0, size * 2));
                            long length = parseHex(content.substring(size * 2)) + 1;
                            long displayLength = Math.min(length, process.options.limitElements);
                            var.setLength(length);
                            var.setAdditional(var.getLength() + "#" + refCount);
                            process.sendCommand(String.format("type summary add -s \"%s\\$${var[0-%d]}\" -n %s", var.getKey(), displayLength-1, var.getKey()));
                            process.sendCommand(String.format("fr v %s[0] --summary %s", var.getName(), var.getKey()));
                        } else {
                            LOG.info(String.format("DBG Error: Invalid debugger response for memory: %s", res.toString()));
                        }
                    }
                });
            }
        }
    }

    private void refineString(GdbVariableObject var, GdbMiResults res) {
        if (!process.options.refineStrings) {
            return;
        }
        String type = res.getString("type");
        if (isString(type)) {
            var.setChildrenCount(0);
            String addressStr = parseAddress(res.getString("value"));
            if (isValidAddress(addressStr)) {
                int size = addressStr.length() > 8 ? 8 : 4;
                boolean hasCP = hasCodepageInfo(type);
                boolean hasRefcount = hasRefcountInfo(type);
                int headSize = size * (1 + (hasRefcount ? 1 : 0) + (hasCP ? 1 : 0));
                process.sendCommand(String.format("-data-read-memory-bytes %s-%d %d", addressStr, headSize, headSize), new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        String content = parseReadMemory(res);
                        if ((content != null) && (content.length() == (headSize * 2))) {
                            int base = 0;
                            Integer elemSize = null;
                            Integer codepage = null;
                            if (hasCP) {
                                codepage = (int) parseHex(content.substring(0, 4));
                                elemSize = (int) parseHex(content.substring(4, 8));
                                base = size * 2;
                            }
                            Long codepageFinal = codepage != null ? codepage.longValue() : null;
                            Long refCount = null;
                            if (hasRefcount) {
                                refCount = parseHex(content.substring(base, base + size * 2));
                                base = base + size * 2;
                            }
                            Long refCountFinal = refCount;
                            long length = parseHex(content.substring(base, base + size * 2));
                            long displayLength = Math.min(length, process.options.limitChars);
                            int charSize = getCharSize(elemSize, type);
                            long dataSize = isSizeInBytes(type) ? displayLength : displayLength * charSize;
                            process.sendCommand(String.format("-data-read-memory-bytes %s %d", addressStr, dataSize), new CommandSender.FinishCallback() {
                                        @Override
                                        public void call(GdbMiLine res) {
                                            String content = parseReadMemory(res);
                                            if (content != null) {
                                                var.setValueRefined(parseString(content, length, displayLength, charSize));
                                                var.setAdditional(length + (refCountFinal != null ? "#" + refCountFinal.toString() : "") + printCodepage(codepageFinal));
                                                updateVariableObjectUI(var);
                                            } else {
                                                LOG.info(String.format("DBG Error: Invalid debugger response for memory: %s", res.toString()));
                                            }
                                        }
                                    }
                            );
                        }
                    }
                });
            }
        }
    }

    private static String formatVariableName(@NotNull PasField field) {
        return field.name + (field.fieldType == PasField.FieldType.ROUTINE ? "()" : "");
    }

    private static String getVarKey(String varName, boolean children, String prefix) {
        return (children ? "" : prefix) + varName.replace(' ', '_');
    }

    private static boolean isHidden(String varName) {
        return varName.startsWith(OPEN_ARRAY_HIGH_BOUND_VAR_PREFIX) || "result".equals(varName);
    }

    private PasField resolveIdentifierName(XSourcePosition sourcePosition, final String name, final Set<PasField.FieldType> types) {
        if (!process.options.resolveNames() || (null == sourcePosition)) {
            return null;
        }
        if (DumbService.isDumb(process.getSession().getProject())) {
            return null;
        } else {
            return ApplicationManager.getApplication().runReadAction(new Computable<PasField>() {
                @Override
                public PasField compute() {
                    PsiElement el = XDebuggerUtil.getInstance().findContextElement(sourcePosition.getFile(), sourcePosition.getOffset(), process.getSession().getProject(), false);
                    if (el != null) {
                        Collection<PasField> fields = getFields(el, name);
                        String id = name.substring(name.lastIndexOf('.') + 1);
                        for (PasField field : fields) {
                            if (types.contains(field.fieldType) && id.equalsIgnoreCase(field.name)) {
                                return field;
                            }
                        }
                    }
                    return null;
                }
            });
        }
    }

    private Collection<PasField> getFields(@NotNull PsiElement el, String name) {
        Collection<PasField> fields = fieldsMap.get(name);
        if (fields != null) {
            return fields;
        }
        NamespaceRec namespace;
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            if (name.startsWith("this.")) {
                namespace = NamespaceRec.fromFQN(el, name.substring(5));
            } else {
                namespace = NamespaceRec.fromFQN(el, name);
            }
        } else {
            namespace = NamespaceRec.fromFQN(el, name);
        }
        namespace.setIgnoreVisibility(true);
        fields = PasReferenceUtil.resolveExpr(namespace, new ResolveContext(PasField.TYPES_LOCAL, true), 0);
        fieldsMap.put(name, fields);
        return fields;
    }

    public void evaluate(GdbStackFrame frame, String expression, XDebuggerEvaluator.XEvaluationCallback callback, XSourcePosition expressionPosition) {
        String key = getVarKey(expression, false, VAR_PREFIX_WATCHES);
        final GdbVariableObject var = new GdbVariableObject(frame, key, expression.toUpperCase(), expression, callback);
        variableObjectMap.put(key, var);
        process.sendCommand("-var-delete " + key, SILENT);
        process.sendCommand(String.format("-var-create %4$s%s%4$s %s \"%s\"", key, process.getVarFrame(), expression.toUpperCase(), process.getVarNameQuoteChar()),
                new CommandSender.FinishCallback() {
                    @Override
                    public void call(GdbMiLine res) {
                        if ((res.getType() == GdbMiLine.Type.RESULT_RECORD) && ("error".equals(res.getRecClass()))) {
                            var.setError(res.getResults().getString("msg"));
                            callback.evaluated(new PascalDebuggerValue(var));
                        } else {
                            handleVarResult(res.getResults());
                        }
                    }
                });
    }

    private String parseAddress(String value) {
        int valuePos = value != null ? value.indexOf(" \\\"") : -1;
        return valuePos < 0 ? value : value.substring(0, valuePos);
    }

    private String parseString(String content, long length, long displayLength, int charSize) {
        try {
            String str = null;
            byte[] data = Hex.decodeHex(content.toCharArray());
            if (charSize == 1) {
                str = new String(data, StandardCharsets.UTF_8);
            } else if (charSize == 2) {
                str = new String(data, StandardCharsets.UTF_16LE);
            }
            if ((str != null) && process.options.showNonPrintable) {
                StringBuilder sb = new StringBuilder(str.length() + str.length() / 4);
                str.chars().forEachOrdered(new IntConsumer() {
                    @Override
                    public void accept(int value) {
                        if (value >= 32) {
                            sb.append((char) value);
                        } else {
                            sb.append("'#").append(value).append('\'');
                        }
                    }
                });
                str = sb.toString();
            }
            String termStr = displayLength == length ? "'" : "...";
            return String.format("'%s%s", str, termStr);
        } catch (DecoderException e) {
            return null;
        }
    }

    private String printCodepage(Long codepage) {
        String mapped = CODEPAGE_MAP.get(codepage);
        return codepage != null ? ",cp" + (mapped != null ? mapped : codepage) : "";
    }

    private String parseReadMemory(GdbMiLine res) {
        List<Object> memory = res.getResults().getValue("memory") != null ? res.getResults().getList("memory") : null;
        GdbMiResults tuple = ((memory != null) && (memory.size() > 0)) ? (GdbMiResults) memory.get(0) : null;
        return tuple != null ? tuple.getString("contents") : null;
    }

    private static boolean isDynamicArray(String type) {
        return (type != null) && type.contains("(*)[]");
    }

    private static boolean isString(String type) {
        return (type != null) && TYPES_STRING.contains(type.toUpperCase());
    }

    private boolean isSizeInBytes(String type) {
        return "UTF8STRING".equalsIgnoreCase(type);
    }

    private int getCharSize(Integer elemSize, String type) {
        String typeUC = type.toUpperCase();
        int charSize = ("UNICODESTRING".equals(typeUC) || "WIDESTRING".equals(typeUC)) ? 2 : 1;
        if ((elemSize != null) && (charSize != elemSize)) {
            LOG.info(String.format("DBG Error: character size not match. type: %s (%d), element size from header: %d", type, charSize, elemSize));
        }
        if ((elemSize != null) && ((elemSize == 1) || (elemSize == 2))) {
            return elemSize;
        } else {
            return charSize;
        }
    }

    private boolean hasCodepageInfo(String type) {
        return "ANSISTRING".equalsIgnoreCase(type);   // TODO: codepage present only since FPC 3.x
    }

    private boolean hasRefcountInfo(String type) {
        return !"WIDESTRING".equalsIgnoreCase(type);
    }

    private boolean isValidAddress(String addressStr) {
        long address = addressStr != null ? decodeLong(addressStr, 0L) : 0;
        return address != 0;
    }

    private long decodeLong(String addressStr, Long defaultValue) {
        try {
            return Long.decode(addressStr);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseHex(String s) {
        int len = s.length();
        long data = 0;
        for (int i = 0; i < len; i += 2) {
            data = data * 256 + ((Character.digit(s.charAt(len - 2 - i), 16) << 4)
                    + Character.digit(s.charAt(len - 1 - i), 16));
        }
        return data;
    }

    private void updateVariableObjectUI(GdbVariableObject var) {
        if (var.getCallback() != null) {
            var.getCallback().evaluated(new PascalDebuggerValue(var));
        }
    }

}
