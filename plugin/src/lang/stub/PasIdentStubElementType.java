package com.siberika.idea.pascal.lang.stub;

import com.intellij.lang.LighterAST;
import com.intellij.lang.LighterASTNode;
import com.intellij.psi.stubs.ILightStubElementType;
import com.intellij.psi.stubs.IndexSink;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.util.SmartList;
import com.siberika.idea.pascal.PascalLanguage;
import com.siberika.idea.pascal.lang.psi.PascalIdentDecl;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.psi.impl.PascalIdentDeclImpl;
import com.siberika.idea.pascal.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class PasIdentStubElementType extends ILightStubElementType<PasIdentStub, PascalIdentDecl> {

    public static PasIdentStubElementType INSTANCE;

    public PasIdentStubElementType(String debugName) {
        super(debugName, PascalLanguage.INSTANCE);
        INSTANCE = this;
    }

    @Override
    public PasIdentStub createStub(LighterAST tree, LighterASTNode node, StubElement parentStub) {
        return new PasIdentStubImpl(parentStub, "-", PasField.FieldType.VARIABLE, null, null, Collections.emptyList());
    }

    @Override
    public PascalIdentDecl createPsi(@NotNull PasIdentStub stub) {
        return PascalIdentDeclImpl.create(stub, this);
    }

    @NotNull
    @Override
    public PasIdentStub createStub(@NotNull PascalIdentDecl psi, StubElement parentStub) {
        return new PasIdentStubImpl(parentStub, psi.getName(), PsiUtil.getFieldType(psi), psi.getTypeString(), psi.getTypeKind(), psi.getSubMembers());
    }

    @NotNull
    @Override
    public String getExternalId() {
        return "pas.stub.ident";
    }

    @Override
    public void serialize(@NotNull PasIdentStub stub, @NotNull StubOutputStream dataStream) throws IOException {
        StubUtil.printStub("PasIdentStub.serialize", stub);

        dataStream.writeName(stub.getName());
        dataStream.writeName(stub.getType().name());
        dataStream.writeName(stub.getTypeString());
        dataStream.writeName(stub.getTypeKind() != null ? stub.getTypeKind().name() : StubUtil.ENUM_NULL);
        StubUtil.writeStringCollection(dataStream, stub.getSubMembers());
    }

    @NotNull
    @Override
    public PasIdentStub deserialize(@NotNull StubInputStream dataStream, StubElement parentStub) throws IOException {
        String name = StubUtil.readName(dataStream);
        PasField.FieldType type = StubUtil.readEnum(dataStream, PasField.FieldType.class);
        String typeString = StubUtil.readName(dataStream);
        List<String> subMembers = new SmartList<>();
        PasField.Kind kind = StubUtil.readEnum(dataStream, PasField.Kind.class);
        StubUtil.readStringCollection(dataStream, subMembers);
        return new PasIdentStubImpl(parentStub, name, type, typeString, kind, subMembers);
    }

    @Override
    public void indexStub(@NotNull PasIdentStub stub, @NotNull IndexSink sink) {
        sink.occurrence(PascalSymbolIndex.KEY, stub.getName());
    }
}
