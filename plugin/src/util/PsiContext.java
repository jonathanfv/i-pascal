package com.siberika.idea.pascal.util;

/**
 * Author: George Bakhtadze
 * Date: 06/01/2016
 */
public enum PsiContext {
    TYPE_ID, PROPERTY_SPEC, GENERIC_PARAM,
    FOR, CALL,
    FQN_SINGLE, FQN_FIRST, FQN_NEXT,
    USES, EXPORT,

    VAR_DECL, TYPE_DECL, CONST_DECL, PROPERTY_DECL, ENUM, EXCEPTION_HANDLER, FORMAL_PARAMETER, FIELD,
    METHOD_RESOLUTION, ASSEMBLER, MODULE_HEAD, ROUTINE_DECL, VALUE_SPEC, ATTRIBUTE,

    UNKNOWN
}
