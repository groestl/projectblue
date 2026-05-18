package org.pgjava.sql.ast;

/** A generic name=value option element used in CREATE SEQUENCE, etc. */
public record DefElem(String name, Node value) implements Node {}
