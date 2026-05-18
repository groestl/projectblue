lexer grammar PlPgSqlLexer;

options {
    caseInsensitive = true;
}

// =========================================================================
// PL/pgSQL structural keywords
// =========================================================================

KW_DECLARE    : 'DECLARE' ;
KW_BEGIN      : 'BEGIN' ;
KW_END        : 'END' ;
KW_EXCEPTION  : 'EXCEPTION' ;

// Control flow
KW_IF         : 'IF' ;
KW_THEN       : 'THEN' ;
KW_ELSIF      : 'ELSIF' ;
KW_ELSEIF     : 'ELSEIF' ;
KW_ELSE       : 'ELSE' ;
KW_CASE       : 'CASE' ;
KW_WHEN       : 'WHEN' ;
KW_LOOP       : 'LOOP' ;
KW_WHILE      : 'WHILE' ;
KW_FOR        : 'FOR' ;
KW_FOREACH    : 'FOREACH' ;
KW_IN         : 'IN' ;
KW_REVERSE    : 'REVERSE' ;
KW_EXIT       : 'EXIT' ;
KW_CONTINUE   : 'CONTINUE' ;

// Return
KW_RETURN     : 'RETURN' ;
KW_NEXT       : 'NEXT' ;
KW_QUERY      : 'QUERY' ;

// RAISE
KW_RAISE      : 'RAISE' ;
KW_DEBUG      : 'DEBUG' ;
KW_LOG        : 'LOG' ;
KW_INFO       : 'INFO' ;
KW_NOTICE     : 'NOTICE' ;
KW_WARNING    : 'WARNING' ;

// SQL / DML keywords used structurally
KW_PERFORM    : 'PERFORM' ;
KW_EXECUTE    : 'EXECUTE' ;
KW_SELECT     : 'SELECT' ;
KW_INSERT     : 'INSERT' ;
KW_UPDATE     : 'UPDATE' ;
KW_DELETE     : 'DELETE' ;
KW_WITH       : 'WITH' ;
KW_INTO       : 'INTO' ;
KW_STRICT     : 'STRICT' ;
KW_USING      : 'USING' ;
KW_RETURNING  : 'RETURNING' ;
KW_FROM       : 'FROM' ;

// Cursors
KW_OPEN       : 'OPEN' ;
KW_FETCH      : 'FETCH' ;
KW_MOVE       : 'MOVE' ;
KW_CLOSE      : 'CLOSE' ;
KW_CURSOR     : 'CURSOR' ;
KW_SCROLL     : 'SCROLL' ;
KW_PRIOR      : 'PRIOR' ;
KW_FIRST      : 'FIRST' ;
KW_LAST       : 'LAST' ;
KW_ABSOLUTE   : 'ABSOLUTE' ;
KW_RELATIVE   : 'RELATIVE' ;
KW_FORWARD    : 'FORWARD' ;
KW_BACKWARD   : 'BACKWARD' ;
KW_ALL        : 'ALL' ;

// Diagnostics
KW_GET        : 'GET' ;
KW_DIAGNOSTICS : 'DIAGNOSTICS' ;
KW_STACKED    : 'STACKED' ;
KW_ROW_COUNT  : 'ROW_COUNT' ;
KW_RESULT_OID : 'RESULT_OID' ;
KW_PG_CONTEXT : 'PG_CONTEXT' ;

// Declarations
KW_ALIAS      : 'ALIAS' ;
KW_CONSTANT   : 'CONSTANT' ;
KW_NOT        : 'NOT' ;
KW_NULL       : 'NULL' ;
KW_DEFAULT    : 'DEFAULT' ;
KW_RECORD     : 'RECORD' ;
KW_ROWTYPE    : 'ROWTYPE' ;
KW_TYPE       : 'TYPE' ;
KW_SLICE      : 'SLICE' ;
KW_ARRAY      : 'ARRAY' ;

// Misc
KW_ASSERT     : 'ASSERT' ;
KW_OR         : 'OR' ;
KW_AND        : 'AND' ;
KW_BY         : 'BY' ;
KW_IS         : 'IS' ;
KW_AS         : 'AS' ;
KW_OF         : 'OF' ;
KW_TO         : 'TO' ;
KW_NO         : 'NO' ;
KW_FOUND      : 'FOUND' ;
KW_OTHERS     : 'OTHERS' ;
KW_SQLSTATE   : 'SQLSTATE' ;
KW_SQLERRM    : 'SQLERRM' ;
KW_MESSAGE    : 'MESSAGE' ;
KW_DETAIL     : 'DETAIL' ;
KW_HINT       : 'HINT' ;
KW_ERRCODE    : 'ERRCODE' ;
KW_COLUMN_NAME : 'COLUMN_NAME' ;
KW_CONSTRAINT_NAME : 'CONSTRAINT_NAME' ;
KW_DATATYPE_NAME : 'DATATYPE_NAME' ;
KW_TABLE_NAME : 'TABLE_NAME' ;
KW_SCHEMA_NAME : 'SCHEMA_NAME' ;
KW_MESSAGE_TEXT : 'MESSAGE_TEXT' ;

// =========================================================================
// Symbols
// =========================================================================

ASSIGN        : ':=' ;
DOTDOT        : '..' ;
LABEL_START   : '<<' ;
LABEL_END     : '>>' ;
SEMI          : ';' ;
COMMA         : ',' ;
DOT           : '.' ;
LPAREN        : '(' ;
RPAREN        : ')' ;
LBRACKET      : '[' ;
RBRACKET      : ']' ;
COLON         : ':' ;
DOLLAR        : '$' ;
HASH          : '#' ;
PERCENT       : '%' ;
EQUAL         : '=' ;
NOT_EQUAL     : '!=' | '<>' ;
LT            : '<' ;
GT            : '>' ;
LE            : '<=' ;
GE            : '>=' ;
PLUS          : '+' ;
MINUS         : '-' ;
STAR          : '*' ;
SLASH         : '/' ;
CARET         : '^' ;
PIPE          : '||' ;
AT            : '@' ;
TILDE         : '~' ;
BANG          : '!' ;
TYPECAST      : '::' ;
QMARK         : '?' ;

// =========================================================================
// Literals
// =========================================================================

INTEGER_LITERAL
    : [0-9]+
    ;

// Require at least one digit after the dot so that 1..10 tokenizes as
// INTEGER(1) DOTDOT(..) INTEGER(10) rather than NUMERIC(1.) DOT(.) INTEGER(10).
NUMERIC_LITERAL
    : [0-9]+ '.' [0-9]+
    | '.' [0-9]+
    | [0-9]+ ('.' [0-9]+)? 'E' [+-]? [0-9]+
    | '.' [0-9]+ 'E' [+-]? [0-9]+
    ;

// Single-quoted string (with '' escape)
STRING_LITERAL
    : '\'' ( '\'\'' | ~'\'' )* '\''
    ;

// Dollar-quoted string
DOLLAR_STRING
    : DOLLAR_TAG .*? DOLLAR_TAG
    ;

fragment DOLLAR_TAG
    : '$' ([A-Z_] [A-Z_0-9]*)? '$'
    ;

// =========================================================================
// Identifiers
// =========================================================================

// Positional parameter: $1, $2, ...
PARAM_REF
    : '$' [0-9]+
    ;

IDENTIFIER
    : [A-Z_] [A-Z_0-9]*
    ;

QUOTED_IDENTIFIER
    : '"' ( '""' | ~'"' )* '"'
    ;

// =========================================================================
// Whitespace and comments (skip)
// =========================================================================

WS
    : [ \t\r\n]+ -> skip
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// Catch-all for any other character
OTHER
    : .
    ;
