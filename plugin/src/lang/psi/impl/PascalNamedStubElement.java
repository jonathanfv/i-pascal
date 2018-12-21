package com.siberika.idea.pascal.lang.psi.impl;

import com.intellij.extapi.psi.StubBasedPsiElementBase;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.ProjectScopeImpl;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.SmartHashSet;
import com.siberika.idea.pascal.ide.actions.GotoSuper;
import com.siberika.idea.pascal.lang.parser.PascalParserUtil;
import com.siberika.idea.pascal.lang.psi.PasConstDeclaration;
import com.siberika.idea.pascal.lang.psi.PasEntityScope;
import com.siberika.idea.pascal.lang.psi.PasExportedRoutine;
import com.siberika.idea.pascal.lang.psi.PasGenericTypeIdent;
import com.siberika.idea.pascal.lang.psi.PasInterfaceTypeDecl;
import com.siberika.idea.pascal.lang.psi.PasModule;
import com.siberika.idea.pascal.lang.psi.PasNamespaceIdent;
import com.siberika.idea.pascal.lang.psi.PasTypeDecl;
import com.siberika.idea.pascal.lang.psi.PasTypeDeclaration;
import com.siberika.idea.pascal.lang.psi.PasTypes;
import com.siberika.idea.pascal.lang.psi.PasVarDeclaration;
import com.siberika.idea.pascal.lang.psi.PascalNamedElement;
import com.siberika.idea.pascal.lang.psi.PascalQualifiedIdent;
import com.siberika.idea.pascal.lang.psi.PascalStructType;
import com.siberika.idea.pascal.lang.psi.PascalStubElement;
import com.siberika.idea.pascal.lang.psi.PascalVariableDeclaration;
import com.siberika.idea.pascal.lang.references.ResolveUtil;
import com.siberika.idea.pascal.lang.stub.PasNamedStub;
import com.siberika.idea.pascal.util.PsiUtil;
import com.siberika.idea.pascal.util.SyncUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public abstract class PascalNamedStubElement<B extends PasNamedStub> extends StubBasedPsiElementBase<B> implements PascalStubElement<B>, PascalNamedElement {

    PascalNamedStubElement(ASTNode node) {
        super(node);
    }

    PascalNamedStubElement(B stub, IStubElementType nodeType) {
        super(stub, nodeType);
    }

    volatile private String myCachedUniqueName;
    private String myCachedName;
    private ReentrantLock nameLock = new ReentrantLock();
    private String containingUnitName;
    private Boolean local;
    volatile private long modified;

    @Override
    public ItemPresentation getPresentation() {
        return PascalParserUtil.getPresentation(this);
    }

    private void invalidateCaches() {
        if (SyncUtil.lockOrCancel(nameLock)) {
            myCachedName = null;
            myCachedUniqueName = null;
            containingUnitName = null;
            nameLock.unlock();
        }
        local = null;
        modified = getContainingFile().getModificationStamp();
    }

    private void ensureCacheActual() {
        if (modified != getContainingFile().getModificationStamp()) {
            invalidateCaches();
        }
    }

    @NotNull
    @Override
    public String getName() {
        B stub = retrieveStub();
        if (stub != null) {
            return stub.getName();
        }
        ensureCacheActual();
        if (SyncUtil.lockOrCancel(nameLock)) {
            try {
                if ((myCachedName == null) || (myCachedName.length() == 0)) {
                    myCachedName = PascalNamedElementImpl.calcName(getNameElement());
                }
            } finally {
                nameLock.unlock();
            }
        }
        return myCachedName;
    }

    @Override
    public String getNamespace() {
        String name = getName();
        int pos = name.lastIndexOf(".");
        return pos >= 0 ? name.substring(0, pos) : null;
    }

    @Override
    public String getNamePart() {
        String name = getName();
        int pos = name.lastIndexOf(".");
        return pos >= 0 ? name.substring(pos + 1) : name;
    }

    @NotNull
    @Override
    public PasField.FieldType getType() {
        B stub = retrieveStub();
        if (stub != null) {
            return stub.getType();
        }
        return PsiUtil.getFieldType(this);
    }

    @Override
    public boolean isExported() {
        B stub = retrieveStub();
        if (stub != null) {
            return stub.isExported();
        }
        return calcIsExported();
    }

    protected abstract boolean calcIsExported();

    @Override
    public boolean isLocal() {
        ensureCacheActual();
        if (null == local) {
            boolean tempLocal;
            if (this instanceof PasExportedRoutine) {
                if (RoutineUtil.isExternal((PasExportedRoutine) this) || RoutineUtil.isOverridden((PasExportedRoutine) this) || isExported()) {
                    tempLocal = false;
                } else {
                    PasEntityScope scope = ((PasEntityScope) this).getContainingScope();
                    if (scope != null) {
                        if (!scope.isLocal()) {
                            tempLocal = false;
                        } else {
                            Collection<PasEntityScope> structs = new SmartHashSet<>();
                            GotoSuper.retrieveParentInterfaces(structs, scope, 0);
                            tempLocal = true;
                            for (PasEntityScope struct : structs) {
                                if (struct instanceof PasInterfaceTypeDecl) {
                                    if (struct.getRoutine(((PasExportedRoutine) this).getReducedName()) != null) {
                                        tempLocal = false;
                                        break;
                                    }
                                }
                            }
                        }
                    } else {
                        tempLocal = true;
                    }
                }
            } else if (this instanceof PascalStructType) {
                PsiElement parent = getParent().getParent();
                tempLocal = (parent instanceof PasTypeDeclaration) && PsiUtil.isImplementationScope(parent.getParent().getParent());
            } else {
                PsiElement parent = getParent();
                if (parent instanceof PasGenericTypeIdent) {
                    parent = parent.getParent();
                }
                if (!isExported() && ((parent instanceof PasVarDeclaration) || (parent instanceof PasConstDeclaration) || (parent instanceof PasTypeDeclaration))) {
                    tempLocal = PsiUtil.isImplementationScope(parent.getParent().getParent());
                } else {
                    tempLocal = false;
                }
            }
            local = tempLocal;
        }
        return local;
    }

    // Name qualified with container names
    public String getUniqueName() {
        B stub = retrieveStub();
        if (stub != null) {
            return stub.getUniqueName();
        }
        ensureCacheActual();
        if (StringUtils.isEmpty(myCachedUniqueName)) {
            myCachedUniqueName = calcUniqueName();
        }
        return myCachedUniqueName;
    }

    protected abstract String calcUniqueName();

    static String calcScopeUniqueName(@Nullable PasEntityScope scope) {
        String scopeName = scope != null ? scope.getUniqueName() : "";
        if ((null == scopeName) || scopeName.endsWith(".")) {      // anonymous scope
            scopeName = scopeName != null ? scopeName : "" + ResolveUtil.STRUCT_SUFFIX;
            PsiElement parent = scope.getParent();
            if (parent instanceof PasTypeDecl) {
                parent = parent.getParent();
                if (parent instanceof PascalVariableDeclaration) {
                    List<? extends PascalNamedElement> idents = ((PascalVariableDeclaration) parent).getNamedIdentDeclList();
                    if (!idents.isEmpty()) {
                        scopeName = scopeName + idents.get(0).getName();
                    }
                }
            }
        }
        return scopeName;
    }

    public String getContainingUnitName() {
        B stub = retrieveStub();
        if (stub != null) {
            return stub.getContainingUnitName();
        }
        ensureCacheActual();
        SyncUtil.doWithLock(nameLock, () -> {
                    if (StringUtils.isEmpty(containingUnitName)) {
                        containingUnitName = calcContainingUnitName();
                    }
                }
        );
        return containingUnitName;
    }

    private String calcContainingUnitName() {
        PasModule module = PsiUtil.getElementPasModule(this);
        return module != null ? module.getName() : null;
    }

    @NotNull
    @Override
    public SearchScope getUseScope() {
        if (!isExported()) {
            return new LocalSearchScope(this.getContainingFile());
        }
        return new ProjectScopeImpl(getProject(), FileIndexFacade.getInstance(getProject()));
    }

    @Nullable
    @Override
    public PsiElement getNameIdentifier() {
        return getNameElement();
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        PsiElement element = getNameElement();
        if (element != null) {
            PsiElement el = PasElementFactory.createReplacementElement(element, name);
            if (el != null) {
                element.replace(el);
            }
        }
        return this;
    }

    @Nullable
    protected PsiElement getNameElement() {
        if ((this instanceof PasNamespaceIdent) || (this instanceof PascalQualifiedIdent)) {
            return this;
        }
        PsiElement result = findChildByType(PasTypes.NAMESPACE_IDENT);
        if (null == result) {
            PascalNamedElement namedChild = PsiTreeUtil.getChildOfType(this, PascalNamedElement.class);
            result = namedChild != null ? namedChild.getNameIdentifier() : null;
        }
        if (null == result) {
            result = findChildByType(NAME_TYPE_SET);
        }
        return result;
    }

}
