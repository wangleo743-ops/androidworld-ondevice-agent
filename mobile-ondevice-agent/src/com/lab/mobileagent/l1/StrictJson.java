package com.lab.mobileagent.l1;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Strict RFC-style JSON validation before Android's permissive org.json parser. */
public final class StrictJson {
    private static final int MAX_CHARACTERS = 2 * 1024 * 1024;
    private StrictJson() {}

    public static JSONObject parseObject(String text) {
        validate(text);
        try {
            JSONObject value = new JSONObject(text);
            return value;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("invalid_json_object");
        }
    }

    public static void validate(String text) {
        if (text == null || text.length() > MAX_CHARACTERS) fail("invalid_json_size");
        Scanner scanner = new Scanner(text);
        scanner.value(0);
        scanner.space();
        if (scanner.position != text.length()) fail("invalid_json_trailing_data");
    }

    private static void fail(String code) { throw new IllegalArgumentException(code); }

    private static final class Scanner {
        final String text;
        int position;
        int tokens;
        Scanner(String text) { this.text = text; }

        void space() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                position++;
            }
        }

        void value(int depth) {
            if (depth > 48 || ++tokens > 100000) fail("json_complexity_limit");
            space();
            if (position >= text.length()) fail("invalid_json");
            char c = text.charAt(position);
            if (c == '{') object(depth + 1);
            else if (c == '[') array(depth + 1);
            else if (c == '"') string();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else if (c == '-' || digit(c)) number();
            else fail("invalid_json");
        }

        void object(int depth) {
            position++;
            space();
            if (take('}')) return;
            Set<String> fields = new HashSet<String>();
            while (true) {
                space();
                String key = string();
                if (!fields.add(key)) fail("duplicate_json_key");
                if (fields.size() > 4096) fail("json_complexity_limit");
                space();
                expect(':');
                value(depth);
                space();
                if (take('}')) return;
                expect(',');
            }
        }

        void array(int depth) {
            position++;
            space();
            if (take(']')) return;
            while (true) {
                value(depth);
                space();
                if (take(']')) return;
                expect(',');
            }
        }

        String string() {
            expect('"');
            StringBuilder decoded = new StringBuilder();
            boolean closed = false;
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') { closed = true; break; }
                if (c < 0x20) fail("invalid_json_string");
                if (c == '\\') {
                    if (position >= text.length()) fail("invalid_json_string");
                    c = text.charAt(position++);
                    switch (c) {
                        case '"': case '\\': case '/': decoded.append(c); break;
                        case 'b': decoded.append('\b'); break;
                        case 'f': decoded.append('\f'); break;
                        case 'n': decoded.append('\n'); break;
                        case 'r': decoded.append('\r'); break;
                        case 't': decoded.append('\t'); break;
                        case 'u':
                            if (position + 4 > text.length()) fail("invalid_json_string");
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = text.charAt(position++);
                                int n = hex >= '0' && hex <= '9' ? hex - '0' :
                                        hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 :
                                        hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (n < 0) fail("invalid_json_string");
                                code = (code << 4) | n;
                            }
                            decoded.append((char) code);
                            break;
                        default: fail("invalid_json_escape");
                    }
                } else decoded.append(c);
            }
            if (!closed) fail("invalid_json_string");
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(i)))
                        fail("invalid_json_unicode");
                } else if (Character.isLowSurrogate(c)) fail("invalid_json_unicode");
            }
            return decoded.toString();
        }

        void number() {
            int start = position;
            take('-');
            if (take('0')) {
                if (position < text.length() && digit(text.charAt(position))) fail("invalid_json_number");
            } else {
                if (position >= text.length() || text.charAt(position) < '1' || text.charAt(position) > '9')
                    fail("invalid_json_number");
                while (position < text.length() && digit(text.charAt(position))) position++;
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            if (position - start > 64) fail("invalid_json_number");
            try {
                double n = Double.parseDouble(text.substring(start, position));
                if (Double.isInfinite(n) || Double.isNaN(n)) fail("invalid_json_number");
            } catch (NumberFormatException ignored) { fail("invalid_json_number"); }
        }

        void digits() {
            int start = position;
            while (position < text.length() && digit(text.charAt(position))) position++;
            if (start == position) fail("invalid_json_number");
        }

        void literal(String value) {
            if (!text.regionMatches(position, value, 0, value.length())) fail("invalid_json");
            position += value.length();
        }

        boolean take(char c) {
            if (position < text.length() && text.charAt(position) == c) { position++; return true; }
            return false;
        }
        void expect(char c) { if (!take(c)) fail("invalid_json"); }
        static boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}
