package org.pgjava.sql.ast;

public enum ConstrType {
    NULL, NOT_NULL, PRIMARY, UNIQUE, FK, CHECK, DEFAULT,
    GENERATED, IDENTITY, EXCLUSION
}
