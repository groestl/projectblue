parser grammar PlPgSqlParser;

options {
    tokenVocab = PlPgSqlLexer;
}

// =========================================================================
// Top-level body
// =========================================================================

body
    : opt_label decl_section? KW_BEGIN stmt_list exception_section? KW_END opt_end_label? SEMI? EOF
    ;

opt_label
    : LABEL_START IDENTIFIER LABEL_END
    |
    ;

opt_end_label
    : IDENTIFIER
    ;

// =========================================================================
// DECLARE section
// =========================================================================

decl_section
    : KW_DECLARE decl*
    ;

decl
    : var_decl
    | alias_decl
    | cursor_decl
    ;

var_decl
    : IDENTIFIER KW_CONSTANT? data_type copy_type_suffix?
      ( KW_NOT KW_NULL )? var_default? SEMI
    ;

copy_type_suffix
    : PERCENT KW_TYPE
    | PERCENT KW_ROWTYPE
    ;

var_default
    : ( ASSIGN | KW_DEFAULT ) expr_until_semi
    ;

alias_decl
    : IDENTIFIER KW_ALIAS KW_FOR ( PARAM_REF | IDENTIFIER ) SEMI
    ;

cursor_decl
    : IDENTIFIER scroll_option? KW_CURSOR
      ( LPAREN cursor_arg_list RPAREN )? ( KW_FOR | KW_IS ) sql_until_semi SEMI
    ;

scroll_option
    : KW_NO KW_SCROLL
    | KW_SCROLL
    ;

cursor_arg_list
    : cursor_arg ( COMMA cursor_arg )*
    ;

cursor_arg
    : IDENTIFIER data_type
    ;

// =========================================================================
// Data types
// =========================================================================

data_type
    : KW_RECORD
    | qualified_name ( LPAREN type_modifiers RPAREN )? array_bounds*
    ;

qualified_name
    : name_part ( DOT name_part )*
    ;

name_part
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    | unreserved_keyword
    ;

type_modifiers
    : expr_until_rparen
    ;

array_bounds
    : LBRACKET INTEGER_LITERAL? RBRACKET
    | KW_ARRAY ( LBRACKET INTEGER_LITERAL? RBRACKET )?
    ;

// =========================================================================
// Statements
// =========================================================================

stmt_list
    : ( labeled_stmt | stmt SEMI )*
    ;

labeled_stmt
    : LABEL_START IDENTIFIER LABEL_END stmt SEMI
    ;

// IMPORTANT: More specific alternatives MUST come before less specific ones.
// In particular, keyword-prefixed statements must come before assign_stmt
// (which starts with qualified_name, which can match unreserved keywords).
stmt
    : block_stmt
    | if_stmt
    | case_stmt
    | loop_stmt
    | while_stmt
    | for_stmt
    | foreach_stmt
    | return_stmt
    | raise_stmt
    | perform_stmt
    | execute_stmt
    | get_diagnostics_stmt
    | assert_stmt
    | null_stmt
    | exit_stmt
    | continue_stmt
    | open_cursor_stmt
    | fetch_stmt
    | move_stmt
    | close_stmt
    | sql_stmt
    | assign_stmt
    ;

// -------------------------------------------------------------------------
// Nested block: [DECLARE ...] BEGIN ... END
// -------------------------------------------------------------------------

block_stmt
    : opt_label decl_section? KW_BEGIN stmt_list exception_section? KW_END opt_end_label?
    ;

// -------------------------------------------------------------------------
// Assignment: var := expr ;   or   var[idx] := expr ;
// -------------------------------------------------------------------------

assign_stmt
    : assign_target ASSIGN expr_until_semi
    ;

assign_target
    : qualified_name ( LBRACKET expr_until_rbracket RBRACKET )* ( DOT name_part )*
    ;

// -------------------------------------------------------------------------
// IF / ELSIF / ELSE / END IF
// -------------------------------------------------------------------------

if_stmt
    : KW_IF expr_until_then KW_THEN stmt_list
      elsif_clause*
      else_clause?
      KW_END KW_IF
    ;

elsif_clause
    : ( KW_ELSIF | KW_ELSEIF ) expr_until_then KW_THEN stmt_list
    ;

else_clause
    : KW_ELSE stmt_list
    ;

// -------------------------------------------------------------------------
// CASE (simple and searched)
// -------------------------------------------------------------------------

case_stmt
    : KW_CASE expr_until_when?
      case_when_clause+
      else_clause?
      KW_END KW_CASE
    ;

case_when_clause
    : KW_WHEN expr_list_until_then KW_THEN stmt_list
    ;

expr_list_until_then
    : expr_until_comma_or_then ( COMMA expr_until_comma_or_then )*
    ;

// -------------------------------------------------------------------------
// Loops
// -------------------------------------------------------------------------

loop_stmt
    : KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?
    ;

while_stmt
    : KW_WHILE expr_until_loop KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?
    ;

// Combined FOR statement — ANTLR disambiguates by lookahead
for_stmt
    : KW_FOR IDENTIFIER KW_IN KW_REVERSE? expr_until_dotdot DOTDOT expr_until_loop_or_by
      ( KW_BY expr_until_loop )?
      KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?                  # foriStmt

    | KW_FOR for_target KW_IN
      ( KW_SELECT | KW_WITH | KW_INSERT | KW_UPDATE | KW_DELETE | LPAREN )
      sql_until_loop
      KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?                  # forqStmt

    | KW_FOR IDENTIFIER KW_IN IDENTIFIER
      ( LPAREN expr_list_until_rparen RPAREN )?
      KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?                  # forcStmt
    ;

for_target
    : IDENTIFIER ( COMMA IDENTIFIER )*
    ;

// FOREACH var [SLICE n] IN ARRAY expr LOOP
foreach_stmt
    : KW_FOREACH IDENTIFIER ( KW_SLICE INTEGER_LITERAL )? KW_IN KW_ARRAY
      expr_until_loop KW_LOOP stmt_list KW_END KW_LOOP opt_end_label?
    ;

// EXIT [label] [WHEN cond]
exit_stmt
    : KW_EXIT IDENTIFIER? ( KW_WHEN expr_until_semi )?
    ;

// CONTINUE [label] [WHEN cond]
continue_stmt
    : KW_CONTINUE IDENTIFIER? ( KW_WHEN expr_until_semi )?
    ;

// -------------------------------------------------------------------------
// RETURN — combined rule with labeled alternatives
// -------------------------------------------------------------------------

return_stmt
    : KW_RETURN KW_NEXT expr_until_semi                                 # returnNextStmt
    | KW_RETURN KW_QUERY KW_EXECUTE expr_until_semi_or_using
      ( KW_USING expr_list_until_semi )?                                # returnQueryExecuteStmt
    | KW_RETURN KW_QUERY sql_until_semi                                 # returnQuerySqlStmt
    | KW_RETURN expr_until_semi?                                        # returnPlainStmt
    ;

// -------------------------------------------------------------------------
// RAISE
// -------------------------------------------------------------------------

raise_stmt
    : KW_RAISE raise_level? raise_format_or_condname?
      ( COMMA expr_until_comma_or_semi )* raise_using?
    ;

raise_level
    : KW_DEBUG
    | KW_LOG
    | KW_INFO
    | KW_NOTICE
    | KW_WARNING
    | KW_EXCEPTION
    ;

raise_format_or_condname
    : STRING_LITERAL
    | IDENTIFIER
    | KW_SQLSTATE EQUAL? STRING_LITERAL
    ;

raise_using
    : KW_USING raise_option ( COMMA raise_option )*
    ;

raise_option
    : raise_option_name EQUAL expr_until_comma_or_semi
    ;

raise_option_name
    : KW_MESSAGE
    | KW_DETAIL
    | KW_HINT
    | KW_ERRCODE
    | KW_COLUMN_NAME
    | KW_CONSTRAINT_NAME
    | KW_DATATYPE_NAME
    | KW_TABLE_NAME
    | KW_SCHEMA_NAME
    ;

// -------------------------------------------------------------------------
// PERFORM, EXECUTE, SQL statements
// -------------------------------------------------------------------------

perform_stmt
    : KW_PERFORM sql_until_semi
    ;

execute_stmt
    : KW_EXECUTE expr_until_semi_into_using
      ( KW_INTO KW_STRICT? into_target )?
      ( KW_USING expr_list_until_semi )?
    ;

into_target
    : qualified_name ( COMMA qualified_name )*
    ;

// SELECT ... INTO [STRICT] target FROM ...
// INSERT/UPDATE/DELETE ... RETURNING ... INTO [STRICT] target
sql_stmt
    : ( KW_SELECT | KW_WITH | KW_INSERT | KW_UPDATE | KW_DELETE ) sql_until_semi
    ;

// -------------------------------------------------------------------------
// GET DIAGNOSTICS
// -------------------------------------------------------------------------

get_diagnostics_stmt
    : KW_GET KW_STACKED? KW_DIAGNOSTICS diagnostics_item ( COMMA diagnostics_item )*
    ;

diagnostics_item
    : IDENTIFIER ( ASSIGN | EQUAL ) diagnostics_tag
    ;

diagnostics_tag
    : KW_ROW_COUNT
    | KW_RESULT_OID
    | KW_PG_CONTEXT
    | KW_MESSAGE_TEXT
    | IDENTIFIER
    ;

// -------------------------------------------------------------------------
// ASSERT
// -------------------------------------------------------------------------

assert_stmt
    : KW_ASSERT expr_until_comma_or_semi ( COMMA expr_until_semi )?
    ;

// -------------------------------------------------------------------------
// NULL (no-op)
// -------------------------------------------------------------------------

null_stmt
    : KW_NULL
    ;

// -------------------------------------------------------------------------
// Cursors
// -------------------------------------------------------------------------

open_cursor_stmt
    : KW_OPEN IDENTIFIER open_cursor_tail
    ;

open_cursor_tail
    : LPAREN expr_list_until_rparen RPAREN                              // bound cursor with args
    | KW_FOR KW_EXECUTE expr_until_semi_or_using
      ( KW_USING expr_list_until_semi )?                                // unbound, for execute
    | KW_NO KW_SCROLL KW_FOR sql_until_semi                            // no scroll, for query
    | KW_SCROLL KW_FOR sql_until_semi                                   // scroll, for query
    | KW_FOR sql_until_semi                                             // unbound, for query
    |                                                                   // bound cursor, no args
    ;

fetch_stmt
    : KW_FETCH fetch_direction? ( KW_FROM | KW_IN )? IDENTIFIER
      KW_INTO into_target
    ;

move_stmt
    : KW_MOVE fetch_direction? ( KW_FROM | KW_IN )? IDENTIFIER
    ;

close_stmt
    : KW_CLOSE IDENTIFIER
    ;

fetch_direction
    : KW_NEXT
    | KW_PRIOR
    | KW_FIRST
    | KW_LAST
    | KW_ABSOLUTE expr_until_from_or_in
    | KW_RELATIVE expr_until_from_or_in
    | KW_FORWARD KW_ALL
    | KW_FORWARD expr_until_from_or_in
    | KW_BACKWARD KW_ALL
    | KW_BACKWARD expr_until_from_or_in
    ;

// =========================================================================
// EXCEPTION section
// =========================================================================

exception_section
    : KW_EXCEPTION exception_handler+
    ;

exception_handler
    : KW_WHEN exception_condition ( KW_OR exception_condition )* KW_THEN stmt_list
    ;

exception_condition
    : KW_OTHERS
    | KW_SQLSTATE STRING_LITERAL
    | qualified_name
    ;

// =========================================================================
// SQL / expression fragments — captured as raw token runs
// =========================================================================

// Balanced expression: handles nested parens/brackets, stops at terminator.
// These rules capture raw token sequences that will be extracted as text
// and re-parsed by the SQL parser at execution time.

expr_until_semi
    : ( paren_group | bracket_group | ~( SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_then
    : ( paren_group | bracket_group | ~( KW_THEN | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_loop
    : ( paren_group | bracket_group | ~( KW_LOOP | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_loop_or_by
    : ( paren_group | bracket_group | ~( KW_LOOP | KW_BY | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_dotdot
    : ( paren_group | bracket_group | ~( DOTDOT | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_when
    : ( paren_group | bracket_group | ~( KW_WHEN | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_comma_or_then
    : ( paren_group | bracket_group | ~( COMMA | KW_THEN | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_comma_or_semi
    : ( paren_group | bracket_group | ~( COMMA | SEMI | KW_USING | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_rparen
    : ( paren_group | bracket_group | ~( RPAREN | LPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_rbracket
    : ( paren_group | bracket_group | ~( RBRACKET | LPAREN | RPAREN | LBRACKET ) )+
    ;

expr_until_from_or_in
    : ( paren_group | bracket_group | ~( KW_FROM | KW_IN | SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_semi_or_using
    : ( paren_group | bracket_group | ~( SEMI | KW_USING | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_until_semi_into_using
    : ( paren_group | bracket_group | ~( SEMI | KW_INTO | KW_USING | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

expr_list_until_semi
    : expr_until_comma_or_semi ( COMMA expr_until_comma_or_semi )*
    ;

expr_list_until_rparen
    : expr_until_comma_rparen ( COMMA expr_until_comma_rparen )*
    ;

expr_until_comma_rparen
    : ( paren_group | bracket_group | ~( COMMA | RPAREN | LPAREN | LBRACKET | RBRACKET ) )+
    ;

// SQL fragment (full SQL statement body as raw tokens)
sql_until_semi
    : ( paren_group | bracket_group | ~( SEMI | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

sql_until_loop
    : ( paren_group | bracket_group | ~( KW_LOOP | LPAREN | RPAREN | LBRACKET | RBRACKET ) )+
    ;

// =========================================================================
// Balanced grouping helpers
// =========================================================================

paren_group
    : LPAREN ( paren_group | bracket_group | ~( LPAREN | RPAREN | LBRACKET | RBRACKET ) )* RPAREN
    ;

bracket_group
    : LBRACKET ( paren_group | bracket_group | ~( LPAREN | RPAREN | LBRACKET | RBRACKET ) )* RBRACKET
    ;

// =========================================================================
// Unreserved keywords (can be used as identifiers in declarations/types)
// Only keywords that do NOT start statements go here.
// =========================================================================

unreserved_keyword
    : KW_ABSOLUTE | KW_ALIAS | KW_ALL | KW_ARRAY | KW_BACKWARD
    | KW_BY | KW_COLUMN_NAME | KW_CONSTANT | KW_CONSTRAINT_NAME
    | KW_CURSOR | KW_DATATYPE_NAME | KW_DEBUG | KW_DEFAULT
    | KW_DETAIL | KW_DIAGNOSTICS | KW_ERRCODE
    | KW_FIRST | KW_FORWARD
    | KW_FOUND | KW_HINT | KW_INFO | KW_LAST | KW_LOG
    | KW_MESSAGE | KW_MESSAGE_TEXT | KW_NEXT | KW_NO | KW_NOTICE
    | KW_OTHERS | KW_PG_CONTEXT | KW_PRIOR | KW_QUERY
    | KW_RECORD | KW_RELATIVE | KW_RESULT_OID
    | KW_REVERSE | KW_ROW_COUNT | KW_ROWTYPE | KW_SCHEMA_NAME | KW_SCROLL
    | KW_SLICE | KW_SQLERRM | KW_SQLSTATE | KW_STACKED | KW_STRICT
    | KW_TABLE_NAME | KW_TYPE | KW_WARNING
    ;
