package com.lab.mobileagent.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private serialization boundary: caller-owned JSON must never mutate an admitted request. */
final class JsonSnapshot {
    static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (Iterator<String> i = object.keys(); i.hasNext();) keys.add(i.next());
            Collections.sort(keys);
            StringBuilder text = new StringBuilder("{");
            for (String key : keys) {
                if (text.length() > 1) text.append(',');
                text.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return text.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) text.append(',');
                text.append(canonical(array.get(i)));
            }
            return text.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return JSONObject.numberToString((Number) value);
        throw new IllegalArgumentException("unsupported_json_value");
    }
    static String object(JSONObject value) throws Exception {
        if (value == null) throw new IllegalArgumentException("missing_json_object");
        String text = canonical(value);
        if (text.length() > 1024 * 1024) throw new IllegalArgumentException("platform_record_too_large");
        return text;
    }
    static String digest(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static String text(String value, String name, int limit) {
        if (value == null || value.trim().isEmpty() || value.length() > limit || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid_" + name);
        return value;
    }
    private JsonSnapshot() {}
}
