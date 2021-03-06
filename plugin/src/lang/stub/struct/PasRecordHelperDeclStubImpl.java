package com.siberika.idea.pascal.lang.stub.struct;

import com.intellij.psi.stubs.StubElement;
import com.siberika.idea.pascal.lang.psi.PascalRecordHelperDecl;

import java.util.List;

public class PasRecordHelperDeclStubImpl extends PasStructStubImpl<PascalRecordHelperDecl> implements PasRecordHelperDeclStub {
    public PasRecordHelperDeclStubImpl(StubElement parent, String name, List<String> parentNames, List<String> aliases,
                                       PasRecordHelperDeclStubElementType stubElementType) {
        super(parent, name, parentNames, aliases, stubElementType);
    }
}
