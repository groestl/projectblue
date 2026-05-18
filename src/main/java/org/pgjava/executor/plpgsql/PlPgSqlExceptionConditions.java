package org.pgjava.executor.plpgsql;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps PL/pgSQL exception condition names to their SQLSTATE codes,
 * matching PostgreSQL Appendix A — Error Codes.
 *
 * <p>Condition names are stored lower-case with underscores (e.g. {@code "division_by_zero"}).
 * The mapping covers all named conditions that PostgreSQL recognizes in
 * {@code EXCEPTION WHEN} handlers and {@code RAISE ... USING ERRCODE = '...'}.
 */
public final class PlPgSqlExceptionConditions {

    private PlPgSqlExceptionConditions() {}

    private static final Map<String, String> NAME_TO_SQLSTATE;

    static {
        var m = new HashMap<String, String>();

        // OTHERS — special pseudo-condition (matches any error except successful_completion)
        m.put("others", null);

        // Class 00 — Successful Completion
        m.put("successful_completion", "00000");

        // Class 01 — Warning
        m.put("warning", "01000");
        m.put("null_value_eliminated_in_set_function", "01003");
        m.put("string_data_right_truncation", "01004");

        // Class 02 — No Data
        m.put("no_data", "02000");
        m.put("no_data_found", "P0002");

        // Class 03 — SQL Statement Not Yet Complete
        m.put("sql_statement_not_yet_complete", "03000");

        // Class 08 — Connection Exception
        m.put("connection_exception", "08000");
        m.put("connection_does_not_exist", "08003");
        m.put("connection_failure", "08006");

        // Class 09 — Triggered Action Exception
        m.put("triggered_action_exception", "09000");

        // Class 0A — Feature Not Supported
        m.put("feature_not_supported", "0A000");

        // Class 0B — Invalid Transaction Initiation
        m.put("invalid_transaction_initiation", "0B000");

        // Class 0F — Locator Exception
        m.put("locator_exception", "0F000");

        // Class 0L — Invalid Grantor
        m.put("invalid_grantor", "0L000");

        // Class 0P — Invalid Role Specification
        m.put("invalid_role_specification", "0P000");

        // Class 20 — Case Not Found
        m.put("case_not_found", "20000");

        // Class 21 — Cardinality Violation
        m.put("cardinality_violation", "21000");

        // Class 22 — Data Exception
        m.put("data_exception", "22000");
        m.put("numeric_value_out_of_range", "22003");
        m.put("string_data_length_mismatch", "22026");
        m.put("null_value_not_allowed", "22004");
        m.put("division_by_zero", "22012");
        m.put("invalid_text_representation", "22P02");
        m.put("invalid_datetime_format", "22007");
        m.put("datetime_field_overflow", "22008");
        m.put("floating_point_exception", "22P01");
        m.put("invalid_escape_character", "22019");
        m.put("invalid_escape_sequence", "22025");
        m.put("most_specific_type_mismatch", "2200G");
        m.put("invalid_parameter_value", "22023");
        m.put("character_not_in_repertoire", "22021");
        m.put("unterminated_c_string", "22024");
        m.put("invalid_indicator_parameter_value", "22010");
        m.put("substring_error", "22011");
        m.put("trim_error", "22027");
        m.put("array_subscript_error", "2202E");
        m.put("invalid_regular_expression", "2201B");
        m.put("zero_length_character_string", "2200F");

        // Class 23 — Integrity Constraint Violation
        m.put("integrity_constraint_violation", "23000");
        m.put("restrict_violation", "23001");
        m.put("not_null_violation", "23502");
        m.put("foreign_key_violation", "23503");
        m.put("unique_violation", "23505");
        m.put("check_violation", "23514");
        m.put("exclusion_violation", "23P01");

        // Class 24 — Invalid Cursor State
        m.put("invalid_cursor_state", "24000");

        // Class 25 — Invalid Transaction State
        m.put("invalid_transaction_state", "25000");
        m.put("active_sql_transaction", "25001");
        m.put("read_only_sql_transaction", "25006");
        m.put("no_active_sql_transaction", "25P01");
        m.put("in_failed_sql_transaction", "25P02");

        // Class 26 — Invalid SQL Statement Name
        m.put("invalid_sql_statement_name", "26000");

        // Class 27 — Triggered Data Change Violation
        m.put("triggered_data_change_violation", "27000");

        // Class 28 — Invalid Authorization Specification
        m.put("invalid_authorization_specification", "28000");
        m.put("invalid_password", "28P01");

        // Class 2B — Dependent Privilege Descriptors Still Exist
        m.put("dependent_privilege_descriptors_still_exist", "2B000");

        // Class 2D — Invalid Transaction Termination
        m.put("invalid_transaction_termination", "2D000");

        // Class 2F — SQL Routine Exception
        m.put("sql_routine_exception", "2F000");
        m.put("function_executed_no_return_statement", "2F005");

        // Class 34 — Invalid Cursor Name
        m.put("invalid_cursor_name", "34000");

        // Class 38 — External Routine Exception
        m.put("external_routine_exception", "38000");

        // Class 39 — External Routine Invocation Exception
        m.put("external_routine_invocation_exception", "39000");

        // Class 3B — Savepoint Exception
        m.put("savepoint_exception", "3B000");
        m.put("invalid_savepoint_specification", "3B001");

        // Class 3D — Invalid Catalog Name
        m.put("invalid_catalog_name", "3D000");

        // Class 3F — Invalid Schema Name
        m.put("invalid_schema_name", "3F000");

        // Class 40 — Transaction Rollback
        m.put("transaction_rollback", "40000");
        m.put("serialization_failure", "40001");
        m.put("transaction_integrity_constraint_violation", "40002");
        m.put("statement_completion_unknown", "40003");
        m.put("deadlock_detected", "40P01");

        // Class 42 — Syntax Error or Access Rule Violation
        m.put("syntax_error_or_access_rule_violation", "42000");
        m.put("syntax_error", "42601");
        m.put("insufficient_privilege", "42501");
        m.put("undefined_table", "42P01");
        m.put("undefined_column", "42703");
        m.put("undefined_function", "42883");
        m.put("undefined_object", "42704");
        m.put("duplicate_column", "42701");
        m.put("duplicate_alias", "42712");
        m.put("duplicate_object", "42710");
        m.put("duplicate_function", "42723");
        m.put("duplicate_table", "42P07");
        m.put("ambiguous_column", "42702");
        m.put("ambiguous_function", "42725");
        m.put("grouping_error", "42803");
        m.put("datatype_mismatch", "42804");
        m.put("wrong_object_type", "42809");
        m.put("invalid_column_reference", "42P10");
        m.put("invalid_column_definition", "42611");
        m.put("invalid_table_definition", "42P16");
        m.put("invalid_object_definition", "42P17");
        m.put("indeterminate_datatype", "42P18");
        m.put("collation_mismatch", "42P21");
        m.put("invalid_foreign_key", "42830");
        m.put("cannot_coerce", "42846");
        m.put("name_too_long", "42622");
        m.put("reserved_name", "42939");
        m.put("undefined_parameter", "42P02");

        // Class 44 — WITH CHECK OPTION Violation
        m.put("with_check_option_violation", "44000");

        // Class 53 — Insufficient Resources
        m.put("insufficient_resources", "53000");
        m.put("disk_full", "53100");
        m.put("out_of_memory", "53200");
        m.put("too_many_connections", "53300");
        m.put("configuration_limit_exceeded", "53400");

        // Class 54 — Program Limit Exceeded
        m.put("program_limit_exceeded", "54000");
        m.put("statement_too_complex", "54001");
        m.put("too_many_columns", "54011");
        m.put("too_many_arguments", "54023");

        // Class 55 — Object Not In Prerequisite State
        m.put("object_not_in_prerequisite_state", "55000");
        m.put("object_in_use", "55006");
        m.put("cant_change_runtime_param", "55P02");
        m.put("lock_not_available", "55P03");

        // Class 57 — Operator Intervention
        m.put("operator_intervention", "57000");
        m.put("query_canceled", "57014");
        m.put("admin_shutdown", "57P01");
        m.put("crash_shutdown", "57P02");
        m.put("cannot_connect_now", "57P03");
        m.put("database_dropped", "57P04");

        // Class 58 — System Error
        m.put("system_error", "58000");
        m.put("io_error", "58030");
        m.put("undefined_file", "58P01");
        m.put("duplicate_file", "58P02");

        // Class F0 — Configuration File Error
        m.put("config_file_error", "F0000");
        m.put("lock_file_exists", "F0001");

        // Class HV — Foreign Data Wrapper Error
        m.put("fdw_error", "HV000");

        // Class P0 — PL/pgSQL Error
        m.put("plpgsql_error", "P0000");
        m.put("raise_exception", "P0001");
        m.put("too_many_rows", "P0003");
        m.put("assert_failure", "P0004");

        // Class XX — Internal Error
        m.put("internal_error", "XX000");
        m.put("data_corrupted", "XX001");
        m.put("index_corrupted", "XX002");

        NAME_TO_SQLSTATE = Collections.unmodifiableMap(m);
    }

    /**
     * Resolve a condition name to its SQLSTATE code.
     *
     * @param conditionName  lower-case condition name (e.g. "division_by_zero")
     * @return the 5-character SQLSTATE code, or {@code null} for "others"
     * @throws IllegalArgumentException if the condition name is not recognized
     */
    public static String toSqlState(String conditionName) {
        String normalized = conditionName.toLowerCase().replace(' ', '_');
        if (!NAME_TO_SQLSTATE.containsKey(normalized)) {
            throw new IllegalArgumentException("unrecognized exception condition: " + conditionName);
        }
        return NAME_TO_SQLSTATE.get(normalized);
    }

    /**
     * Check whether a given SQLSTATE matches a condition name.
     *
     * <p>A condition name matches if:
     * <ul>
     *   <li>it is "others" (matches everything except successful_completion)</li>
     *   <li>its SQLSTATE equals the error's SQLSTATE exactly</li>
     *   <li>it is a class-level condition and the error's SQLSTATE class matches</li>
     * </ul>
     */
    public static boolean matches(String conditionName, String errorSqlState) {
        if (errorSqlState == null) return false;
        String normalized = conditionName.toLowerCase().replace(' ', '_');

        // OTHERS matches everything except class 00 (successful completion)
        if ("others".equals(normalized)) {
            return !errorSqlState.startsWith("00");
        }

        // Direct condition name → SQLSTATE lookup
        String condState = NAME_TO_SQLSTATE.get(normalized);
        if (condState == null) {
            // Not a known condition name — treat as a SQLSTATE literal
            return conditionName.equalsIgnoreCase(errorSqlState);
        }

        // Exact match
        if (condState.equals(errorSqlState)) return true;

        // Class-level match: if the condition is a class condition (ends in 000),
        // match any SQLSTATE in that class
        if (condState.endsWith("000")) {
            String condClass = condState.substring(0, 2);
            return errorSqlState.startsWith(condClass);
        }

        return false;
    }
}
