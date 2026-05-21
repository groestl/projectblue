package org.pgjava.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.pgjava.engine.PgErrorException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON/JSONB operator and function implementations.
 *
 * <p>All operators accept Java String values (the internal representation of
 * JSON/JSONB in pgjava) and return either String (JSON result) or a Java
 * scalar (text, boolean, numeric) depending on the operator.
 *
 * <p>Uses Jackson for parsing. Parsing is on-demand per operation — acceptable
 * for the small datasets pgjava targets.
 */
public final class JsonOps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonOps() {}

    // -------------------------------------------------------------------------
    // Parsing

    /** Parse a JSON string to a Jackson tree. Throws 22032 on invalid JSON. */
    public static JsonNode parse(String json) throws SQLException {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw PgErrorException.error("22032",
                    "invalid input syntax for type json").detail(e.getMessage()).build();
        }
    }

    /** Serialize a JsonNode back to compact JSON string. */
    public static String toJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.toString();
    }

    /** Extract the text value of a JSON scalar (for ->> and #>>). */
    public static String toText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isValueNode()) return node.asText();
        // For objects and arrays, return JSON representation
        return node.toString();
    }

    // -------------------------------------------------------------------------
    // Operators: -> and ->>

    /**
     * {@code json -> int} — extract array element (0-based).
     * {@code json -> text} — extract object field.
     * Returns JSON string, or null if path doesn't exist.
     */
    public static String extractJson(String json, Object key) throws SQLException {
        if (json == null || key == null) return null;
        JsonNode root = parse(json);
        JsonNode result = extractByKey(root, key);
        return toJson(result);
    }

    /**
     * {@code json ->> int} — extract array element as text.
     * {@code json ->> text} — extract object field as text.
     */
    public static String extractText(String json, Object key) throws SQLException {
        if (json == null || key == null) return null;
        JsonNode root = parse(json);
        JsonNode result = extractByKey(root, key);
        return toText(result);
    }

    private static JsonNode extractByKey(JsonNode root, Object key) {
        if (key instanceof Number n) {
            int idx = n.intValue();
            if (!root.isArray()) return null;
            // PostgreSQL supports negative indexing from the end
            if (idx < 0) idx = root.size() + idx;
            if (idx < 0 || idx >= root.size()) return null;
            return root.get(idx);
        }
        if (key instanceof String s) {
            if (!root.isObject()) return null;
            JsonNode child = root.get(s);
            return (child == null || child.isMissingNode()) ? null : child;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Operators: #> and #>>

    /**
     * {@code json #> text[]} — extract at path (array of keys).
     * Returns JSON string.
     */
    public static String extractPath(String json, Object pathArray) throws SQLException {
        if (json == null || pathArray == null) return null;
        JsonNode node = navigatePath(json, pathArray);
        return toJson(node);
    }

    /**
     * {@code json #>> text[]} — extract at path as text.
     */
    public static String extractPathText(String json, Object pathArray) throws SQLException {
        if (json == null || pathArray == null) return null;
        JsonNode node = navigatePath(json, pathArray);
        return toText(node);
    }

    /**
     * Convert a path argument to a List of path segments.
     * Handles: List<?> as-is, Object[] arrays, and PostgreSQL array literal strings like "{a,b,c}".
     */
    private static List<String> toPathList(Object pathArray) {
        if (pathArray instanceof List<?> l) {
            List<String> result = new ArrayList<>(l.size());
            for (Object o : l) result.add(o == null ? null : o.toString());
            return result;
        }
        if (pathArray instanceof Object[] a) {
            List<String> result = new ArrayList<>(a.length);
            for (Object o : a) result.add(o == null ? null : o.toString());
            return result;
        }
        String s = pathArray.toString().trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            // Parse PostgreSQL array literal: {a,b,c} or {"a b","c"}
            String inner = s.substring(1, s.length() - 1);
            if (inner.isEmpty()) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            int i = 0;
            while (i < inner.length()) {
                if (inner.charAt(i) == '"') {
                    // Quoted element
                    int j = i + 1;
                    StringBuilder elem = new StringBuilder();
                    while (j < inner.length() && inner.charAt(j) != '"') {
                        if (inner.charAt(j) == '\\') j++; // skip escape
                        elem.append(inner.charAt(j++));
                    }
                    result.add(elem.toString());
                    j++; // skip closing quote
                    if (j < inner.length() && inner.charAt(j) == ',') j++;
                    i = j;
                } else {
                    // Unquoted element
                    int j = inner.indexOf(',', i);
                    if (j < 0) j = inner.length();
                    result.add(inner.substring(i, j));
                    i = j + 1;
                }
            }
            return result;
        }
        // Single segment
        return List.of(s);
    }

    private static JsonNode navigatePath(String json, Object pathArray) throws SQLException {
        JsonNode current = parse(json);
        List<String> path = toPathList(pathArray);
        for (String seg : path) {
            if (current == null || current.isMissingNode() || current.isNull()) return null;
            if (current.isArray()) {
                try {
                    int idx = Integer.parseInt(seg);
                    if (idx < 0) idx = current.size() + idx;
                    if (idx < 0 || idx >= current.size()) return null;
                    current = current.get(idx);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (current.isObject()) {
                current = current.get(seg);
                if (current == null || current.isMissingNode()) return null;
            } else {
                return null;
            }
        }
        return current;
    }

    // -------------------------------------------------------------------------
    // Operator: @> (contains) for JSONB

    /**
     * {@code jsonb @> jsonb} — does left contain right?
     *
     * <p>PostgreSQL semantics: every key/value in right exists in left.
     * Array containment: every element in right exists somewhere in left.
     */
    public static Boolean jsonContains(String left, String right) throws SQLException {
        if (left == null || right == null) return null;
        JsonNode l = parse(left);
        JsonNode r = parse(right);
        return containsNode(l, r);
    }

    /**
     * {@code jsonb <@ jsonb} — is left contained in right?
     */
    public static Boolean jsonContainedBy(String left, String right) throws SQLException {
        if (left == null || right == null) return null;
        JsonNode l = parse(left);
        JsonNode r = parse(right);
        return containsNode(r, l);
    }

    private static boolean containsNode(JsonNode container, JsonNode contained) {
        if (contained.isObject()) {
            if (!container.isObject()) return false;
            Iterator<String> fields = contained.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode containerChild = container.get(field);
                if (containerChild == null) return false;
                if (!containsNode(containerChild, contained.get(field))) return false;
            }
            return true;
        }
        if (contained.isArray()) {
            if (!container.isArray()) return false;
            for (JsonNode element : contained) {
                boolean found = false;
                for (JsonNode ce : container) {
                    if (containsNode(ce, element)) { found = true; break; }
                }
                if (!found) return false;
            }
            return true;
        }
        // Scalar: direct equality
        return container.equals(contained);
    }

    // -------------------------------------------------------------------------
    // Operator: ? (key/element exists)

    /**
     * {@code jsonb ? text} — does the key exist (object) or element exist (array)?
     */
    public static Boolean keyExists(String json, String key) throws SQLException {
        if (json == null || key == null) return null;
        JsonNode node = parse(json);
        if (node.isObject()) return node.has(key);
        if (node.isArray()) {
            for (JsonNode el : node) {
                if (el.isTextual() && el.textValue().equals(key)) return true;
            }
            return false;
        }
        return false;
    }

    /**
     * {@code jsonb ?| text[]} — do any of the keys exist?
     */
    public static Boolean anyKeyExists(String json, Object keys) throws SQLException {
        if (json == null || keys == null) return null;
        JsonNode node = parse(json);
        List<?> keyList = (keys instanceof List<?> l) ? l : List.of(keys.toString());
        for (Object k : keyList) {
            if (Boolean.TRUE.equals(keyExists(json, k.toString()))) return true;
        }
        return false;
    }

    /**
     * {@code jsonb ?& text[]} — do all of the keys exist?
     */
    public static Boolean allKeysExist(String json, Object keys) throws SQLException {
        if (json == null || keys == null) return null;
        JsonNode node = parse(json);
        List<?> keyList = (keys instanceof List<?> l) ? l : List.of(keys.toString());
        for (Object k : keyList) {
            if (!Boolean.TRUE.equals(keyExists(json, k.toString()))) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Operator: || (JSON concatenation)

    /**
     * {@code jsonb || jsonb} — concatenate two JSONB values.
     *
     * <p>Objects: merge (right overwrites). Arrays: append. Mixed: wrap in array.
     */
    public static String jsonConcat(String left, String right) throws SQLException {
        if (left == null) return right;
        if (right == null) return left;
        JsonNode l = parse(left);
        JsonNode r = parse(right);
        if (l.isObject() && r.isObject()) {
            ObjectNode merged = ((ObjectNode) l).deepCopy();
            merged.setAll((ObjectNode) r);
            return merged.toString();
        }
        if (l.isArray() && r.isArray()) {
            ArrayNode merged = ((ArrayNode) l).deepCopy();
            merged.addAll((ArrayNode) r);
            return merged.toString();
        }
        if (l.isArray()) {
            ArrayNode arr = ((ArrayNode) l).deepCopy();
            arr.add(r);
            return arr.toString();
        }
        if (r.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            arr.add(l);
            arr.addAll((ArrayNode) r);
            return arr.toString();
        }
        // Both scalars — wrap in array
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(l);
        arr.add(r);
        return arr.toString();
    }

    // -------------------------------------------------------------------------
    // Operator: - (delete key/element)

    /**
     * {@code jsonb - text} — delete key from object.
     * {@code jsonb - int} — delete element at index from array.
     */
    public static String jsonDelete(String json, Object key) throws SQLException {
        if (json == null || key == null) return null;
        JsonNode node = parse(json);
        if (key instanceof Number n && node.isArray()) {
            ArrayNode arr = ((ArrayNode) node).deepCopy();
            int idx = n.intValue();
            if (idx < 0) idx = arr.size() + idx;
            if (idx >= 0 && idx < arr.size()) arr.remove(idx);
            return arr.toString();
        }
        if (key instanceof String s && node.isObject()) {
            ObjectNode obj = ((ObjectNode) node).deepCopy();
            obj.remove(s);
            return obj.toString();
        }
        return json;
    }

    /**
     * {@code jsonb #- text[]} — delete the value at the specified path.
     */
    public static String jsonDeletePath(String json, Object pathArray) throws SQLException {
        if (json == null || pathArray == null) return null;
        List<String> path = toPathList(pathArray);
        if (path.isEmpty()) return json;
        JsonNode root = parse(json).deepCopy();
        if (path.size() == 1) {
            String key = path.get(0);
            if (root.isObject()) { ((ObjectNode) root).remove(key); }
            else if (root.isArray()) {
                try { ((ArrayNode) root).remove(Integer.parseInt(key)); }
                catch (NumberFormatException ignored) {}
            }
            return root.toString();
        }
        // Navigate to parent
        JsonNode current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String seg = path.get(i);
            JsonNode next = navigateOne(current, seg);
            if (next == null || next.isMissingNode()) return json; // path not found
            current = next;
        }
        String last = path.get(path.size() - 1);
        if (current.isObject()) { ((ObjectNode) current).remove(last); }
        else if (current.isArray()) {
            try { ((ArrayNode) current).remove(Integer.parseInt(last)); }
            catch (NumberFormatException ignored) {}
        }
        return root.toString();
    }

    // -------------------------------------------------------------------------
    // Functions

    /** jsonb_typeof / json_typeof — returns the type name of the top-level value. */
    public static String jsonTypeof(String json) throws SQLException {
        if (json == null) return null;
        JsonNode node = parse(json);
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "null";
    }

    /** json_array_length / jsonb_array_length — number of elements in top-level array. */
    public static Object jsonArrayLength(String json) throws SQLException {
        if (json == null) return null;
        JsonNode node = parse(json);
        if (!node.isArray()) {
            throw PgErrorException.error("22023",
                    "cannot get array length of a non-array").build();
        }
        return node.size();
    }

    /** json_object_keys / jsonb_object_keys — returns set of keys (as List for SRF). */
    public static List<Object[]> jsonObjectKeys(String json) throws SQLException {
        if (json == null) return List.of();
        JsonNode node = parse(json);
        if (!node.isObject()) {
            throw PgErrorException.error("22023",
                    "cannot call json_object_keys on a non-object").build();
        }
        List<Object[]> keys = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(f -> keys.add(new Object[]{f}));
        return keys;
    }

    /** jsonb_set(target, path, new_value [, create_if_missing]) */
    public static String jsonbSet(String target, Object pathArray, String newValue,
                                   boolean createIfMissing) throws SQLException {
        if (target == null || pathArray == null || newValue == null) return null;
        JsonNode root = parse(target);
        JsonNode replacement = parse(newValue);
        List<String> path = toPathList(pathArray);
        if (path.isEmpty()) return toJson(replacement);

        // Navigate to parent, set at last key
        JsonNode current = root;
        // We need a mutable copy
        root = root.deepCopy();
        current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String seg = path.get(i);
            JsonNode next = navigateOne(current, seg);
            if (next == null || next.isMissingNode()) {
                if (!createIfMissing) return toJson(root);
                // Create intermediate object when createIfMissing is true
                if (current.isObject()) {
                    ObjectNode obj = (ObjectNode) current;
                    ObjectNode newIntermediate = MAPPER.createObjectNode();
                    obj.set(seg, newIntermediate);
                    current = newIntermediate;
                } else {
                    // Can't create intermediate path in non-object
                    return toJson(root);
                }
            } else {
                current = next;
            }
        }
        String lastKey = path.get(path.size() - 1);
        if (current.isObject()) {
            if (!createIfMissing && !current.has(lastKey)) return toJson(root);
            ((ObjectNode) current).set(lastKey, replacement);
        } else if (current.isArray()) {
            try {
                int idx = Integer.parseInt(lastKey);
                ArrayNode arr = (ArrayNode) current;
                if (idx < 0) idx = arr.size() + idx;
                if (idx >= 0 && idx < arr.size()) {
                    arr.set(idx, replacement);
                } else if (createIfMissing && idx == arr.size()) {
                    arr.add(replacement);
                }
            } catch (NumberFormatException e) {
                // Can't index array with non-integer
            }
        }
        return toJson(root);
    }

    private static JsonNode navigateOne(JsonNode node, String segment) {
        if (node.isObject()) return node.get(segment);
        if (node.isArray()) {
            try {
                int idx = Integer.parseInt(segment);
                if (idx < 0) idx = node.size() + idx;
                if (idx >= 0 && idx < node.size()) return node.get(idx);
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return null;
    }

    /** json_build_object(k1, v1, k2, v2, ...) */
    public static String jsonBuildObject(Object[] args) throws SQLException {
        if (args.length % 2 != 0) {
            throw PgErrorException.error("22023",
                    "argument list must have even number of elements").build();
        }
        ObjectNode obj = MAPPER.createObjectNode();
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i] == null ? "null" : args[i].toString();
            Object val = args[i + 1];
            if (val == null) obj.putNull(key);
            else if (val instanceof Boolean b) obj.put(key, b);
            else if (val instanceof Integer n) obj.put(key, n);
            else if (val instanceof Long n) obj.put(key, n);
            else if (val instanceof Double n) obj.put(key, n);
            else if (val instanceof Float n) obj.put(key, n);
            else if (val instanceof java.math.BigDecimal n) obj.put(key, n);
            else obj.put(key, val.toString());
        }
        return obj.toString();
    }

    /** json_build_array(v1, v2, ...) */
    /** Build a JSON array from a List of Java values. Used by json_agg / jsonb_agg. */
    public static String toJsonArray(java.util.List<Object> elems) {
        return jsonBuildArray(elems.toArray());
    }

    public static String jsonBuildArray(Object[] args) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Object val : args) {
            if (val == null) arr.addNull();
            else if (val instanceof Boolean b) arr.add(b);
            else if (val instanceof Integer n) arr.add(n);
            else if (val instanceof Long n) arr.add(n);
            else if (val instanceof Double n) arr.add(n);
            else if (val instanceof Float n) arr.add(n);
            else if (val instanceof java.math.BigDecimal n) arr.add(n);
            else arr.add(val.toString());
        }
        return arr.toString();
    }

    /** to_json / to_jsonb — convert a value to JSON. */
    public static String toJsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) {
            // PG's to_json(text) always produces a JSON string, never re-interprets as number/bool
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (val instanceof Boolean || val instanceof Number) return val.toString();
        return "\"" + val.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Build a JSON object from a LinkedHashMap (for json_object_agg). */
    public static String jsonObjectFromMap(java.util.Map<String, Object> map) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (var entry : map.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key == null) continue;
            if (val == null) obj.putNull(key);
            else if (val instanceof Boolean b) obj.put(key, b);
            else if (val instanceof Integer n) obj.put(key, n);
            else if (val instanceof Long n) obj.put(key, n);
            else if (val instanceof Double n) obj.put(key, n);
            else if (val instanceof java.math.BigDecimal n) obj.put(key, n);
            else obj.put(key, val.toString());
        }
        return obj.toString();
    }

    /**
     * json_object(keys, values) — build a JSON object from two arrays of keys/values.
     * Both args may be a List, Object[], or a PG array literal string like '{a,b,c}'.
     */
    public static String jsonObjectFromArrays(Object keysArg, Object valsArg) throws SQLException {
        List<String> keys = toStringList(keysArg);
        List<String> vals = toStringList(valsArg);
        ObjectNode obj = MAPPER.createObjectNode();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String val = i < vals.size() ? vals.get(i) : null;
            if (val == null) obj.putNull(key);
            else obj.put(key, val);
        }
        return obj.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object arr) {
        if (arr == null) return List.of();
        if (arr instanceof List<?> l) return l.stream().map(e -> e == null ? null : e.toString()).toList();
        if (arr instanceof Object[] a) {
            List<String> out = new java.util.ArrayList<>();
            for (Object e : a) out.add(e == null ? null : e.toString());
            return out;
        }
        // PostgreSQL array literal: {a,b,c}
        String s = arr.toString().strip();
        if (s.startsWith("{") && s.endsWith("}")) {
            String inner = s.substring(1, s.length() - 1);
            if (inner.isEmpty()) return List.of();
            List<String> out = new java.util.ArrayList<>();
            for (String part : inner.split(",")) out.add(part.strip());
            return out;
        }
        return List.of(s);
    }

    /** row_to_json — convert a row (Object[]) with column names to JSON object. */
    public static String rowToJson(Object[] row, String[] columnNames) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (int i = 0; i < row.length && i < columnNames.length; i++) {
            String key = columnNames[i];
            Object val = row[i];
            if (val == null) obj.putNull(key);
            else if (val instanceof Boolean b) obj.put(key, b);
            else if (val instanceof Integer n) obj.put(key, n);
            else if (val instanceof Long n) obj.put(key, n);
            else if (val instanceof Double n) obj.put(key, n);
            else if (val instanceof java.math.BigDecimal n) obj.put(key, n);
            else obj.put(key, val.toString());
        }
        return obj.toString();
    }

    /** jsonb_each / json_each — return key/value pairs as rows. */
    public static List<Object[]> jsonEach(String json, boolean asText) throws SQLException {
        if (json == null) return List.of();
        JsonNode node = parse(json);
        if (!node.isObject()) {
            throw PgErrorException.error("22023",
                    "cannot call json_each on a non-object").build();
        }
        List<Object[]> rows = new java.util.ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String value = asText ? toText(entry.getValue()) : toJson(entry.getValue());
            rows.add(new Object[]{key, value});
        });
        return rows;
    }

    /** jsonb_array_elements / json_array_elements — unnest array to rows. */
    public static List<Object[]> jsonArrayElements(String json, boolean asText) throws SQLException {
        if (json == null) return List.of();
        JsonNode node = parse(json);
        if (!node.isArray()) {
            throw PgErrorException.error("22023",
                    "cannot call json_array_elements on a non-array").build();
        }
        List<Object[]> rows = new java.util.ArrayList<>();
        for (JsonNode el : node) {
            String value = asText ? toText(el) : toJson(el);
            rows.add(new Object[]{value});
        }
        return rows;
    }

    /** jsonb_strip_nulls — recursively remove null-valued keys from objects. */
    public static String jsonStripNulls(String json) throws SQLException {
        if (json == null) return null;
        JsonNode node = parse(json);
        return toJson(stripNulls(node));
    }

    private static JsonNode stripNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    obj.set(entry.getKey(), stripNulls(entry.getValue()));
                }
            });
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (JsonNode el : node) arr.add(stripNulls(el));
            return arr;
        }
        return node;
    }

    /** jsonb_pretty — pretty-print JSON. */
    public static String jsonPretty(String json) throws SQLException {
        if (json == null) return null;
        JsonNode node = parse(json);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
