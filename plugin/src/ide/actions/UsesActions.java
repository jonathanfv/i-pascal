package com.siberika.idea.pascal.ide.actions;

import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.SmartList;
import com.siberika.idea.pascal.lang.PascalImportOptimizer;
import com.siberika.idea.pascal.lang.psi.PasNamespaceIdent;
import com.siberika.idea.pascal.lang.psi.PasUsesClause;
import com.siberika.idea.pascal.util.PsiUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Author: George Bakhtadze
 * Date: 21/12/2015
 */
public class UsesActions {

    public static class ExcludeUnitAction extends BaseUsesUnitAction {
        public ExcludeUnitAction(String name, PasNamespaceIdent usedUnitName) {
            super(name, usedUnitName);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
            final Document doc = PsiDocumentManager.getInstance(usedUnitName.getProject()).getDocument(usedUnitName.getContainingFile());
            if (doc != null) {
                doc.insertString(usedUnitName.getTextRange().getStartOffset(), "{!}");
            }
        }
    }

    public static class OptimizeUsesAction extends BaseUsesAction implements LowPriorityAction {
        public OptimizeUsesAction(String name) {
            super(name);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
            PascalImportOptimizer.doProcess(file).run();
        }
    }

    public static class MoveUnitAction extends BaseUsesUnitAction {
        public MoveUnitAction(String name, PasNamespaceIdent usedUnitName) {
            super(name, usedUnitName);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
            TextRange range = getRangeToRemove();
            if (range != null) {
                final Document doc = PsiDocumentManager.getInstance(usedUnitName.getProject()).getDocument(usedUnitName.getContainingFile());
                if (doc != null) {
                    PascalImportOptimizer.addUnitToSection(PsiUtil.getElementPasModule(file), new SmartList<String>(usedUnitName.getName()), false);
                    doc.deleteString(range.getStartOffset(), range.getEndOffset());
                }
            }
        }

    }

    public static class RemoveUnitAction extends BaseUsesUnitAction {
        public RemoveUnitAction(String name, PasNamespaceIdent usedUnitName) {
            super(name, usedUnitName);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
            TextRange range = getRangeToRemove();
            if (range != null) {
                final Document doc = PsiDocumentManager.getInstance(usedUnitName.getProject()).getDocument(usedUnitName.getContainingFile());
                if (doc != null) {
                    doc.deleteString(range.getStartOffset(), range.getEndOffset());
                }
            }
        }
    }

    private static abstract class BaseUsesUnitAction extends BaseUsesAction {
        protected final PasNamespaceIdent usedUnitName;
        public BaseUsesUnitAction(String name, PasNamespaceIdent usedUnitName) {
            super(name);
            this.usedUnitName = usedUnitName;
        }

        protected TextRange getRangeToRemove() {
            PasUsesClause usesInterface = (PasUsesClause) usedUnitName.getParent();
            List<TextRange> ranges = PascalImportOptimizer.getUnitRanges(usesInterface);
            return PascalImportOptimizer.removeUnitFromSection(usedUnitName, usesInterface, ranges, usesInterface.getNamespaceIdentList().size());
        }

    }

    private static abstract class BaseUsesAction extends BaseIntentionAction {
        private final String name;

        private BaseUsesAction(String name) {
            this.name = name;
        }

        @Nls
        @NotNull
        @Override
        public String getFamilyName() {
            return "Pascal";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @NotNull
        @Override
        public String getText() {
            return name;
        }

    }

}
