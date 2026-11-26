package com.lab.mobileagent.l1;

import com.lab.mobileagent.model.Action;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** A single untrusted model proposal. Parsing never grants execution permission. */
public final class Decision {
    public final String kind;
    public final Action action;
    public final String answer;
    public final Authorization authorization;

    /** The model's assessment of this proposal. The host makes its own authorization decision. */
    public static final class Authorization {
        public final String assessment, effect, reasonCode;

        private Authorization(JSONObject claim) throws Exception {
            fields(claim, "assessment", "effect", "reason_code");
            assessment = code(claim, "assessment", "within_scope", "needs_approval", "out_of_scope");
            effect = code(claim, "effect", "search_beijing", "read_forecast", "use_location", "other");
            reasonCode = code(claim, "reason_code", "requested_forecast", "location_not_granted",
                    "unrelated_operation", "unclear_target", "insufficient_evidence");
        }

        private static String code(JSONObject claim, String name, String... allowed) throws Exception {
            String value = string(claim, name);
            if (!Arrays.asList(allowed).contains(value)) invalid("invalid_model_authorization_code");
            return value;
        }
    }

    private Decision(String kind, Action action, String answer, Authorization authorization) {
        this.kind = kind; this.action = action; this.answer = answer; this.authorization = authorization;
    }

    public static Decision parse(String text) throws Exception {
        return parse(text, false);
    }

    public static Decision parse(String text, boolean authorizationEnabled) throws Exception {
        if (text == null || text.length() > 32768) invalid("invalid_decision_size");
        JSONObject value = StrictJson.parseObject(text);
        Authorization authorization = null;
        if (authorizationEnabled) {
            fields(value, "kind", "action", "answer", "authorization");
            Object claim = value.get("authorization");
            if (!(claim instanceof JSONObject)) invalid("invalid_model_authorization");
            authorization = new Authorization((JSONObject) claim);
        } else fields(value, "kind", "action", "answer");
        String kind = string(value, "kind");
        if (kind.equals("finish") || kind.equals("blocked")) {
            String answer = string(value, "answer");
            if (value.get("action") != JSONObject.NULL || answer.trim().isEmpty() || answer.length() > 4000)
                invalid("invalid_final_decision");
            return new Decision(kind, null, answer, authorization);
        }
        if (!kind.equals("action") || value.get("answer") != JSONObject.NULL)
            invalid("invalid_decision_kind");
        Object rawAction = value.get("action");
        if (!(rawAction instanceof JSONObject)) invalid("invalid_action");
        JSONObject action = (JSONObject) rawAction;
        fields(action, "name", "args");
        String name = string(action, "name");
        Object rawArgs = action.get("args");
        if (!(rawArgs instanceof JSONObject)) invalid("invalid_action_parameters");
        JSONObject args = (JSONObject) rawArgs;
        if (name.equals("open_app")) {
            fields(args, "app_name");
            if (!string(args, "app_name").equals("Browser")) invalid("app_not_allowed");
        } else if (name.equals("click")) {
            fields(args, "x", "y");
            coordinate(args, "x"); coordinate(args, "y");
        } else if (name.equals("swipe")) {
            fields(args, "start_x", "start_y", "end_x", "end_y");
            coordinate(args, "start_x"); coordinate(args, "start_y");
            coordinate(args, "end_x"); coordinate(args, "end_y");
        } else if (name.equals("type")) {
            fields(args, "text", "press_enter");
            String input = string(args, "text");
            if (input.length() > 2000 || input.contains("%s") || !(args.get("press_enter") instanceof Boolean))
                invalid("invalid_text");
            for (int i = 0; i < input.length(); i++)
                if (input.charAt(i) < 32 || input.charAt(i) > 126) invalid("invalid_text");
        } else if (name.equals("press_key")) {
            fields(args, "key");
            if (!Arrays.asList("BACK", "HOME", "ENTER", "DEL", "TAB", "ESCAPE").contains(string(args, "key")))
                invalid("invalid_key");
        } else if (name.equals("wait")) {
            fields(args, "seconds");
            Object seconds = args.get("seconds");
            if (!(seconds instanceof Number)) invalid("invalid_wait");
            double number = ((Number) seconds).doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number) || number < 0 || number > 10)
                invalid("invalid_wait");
        } else if (name.equals("go_back") || name.equals("take_screenshot")) {
            fields(args);
        } else invalid("unsupported_action");
        return new Decision(kind, new Action(name, args), null, authorization);
    }

    private static void coordinate(JSONObject args, String key) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) invalid("invalid_coordinate");
        long n = ((Number) value).longValue();
        if (n < 0 || n > 999) invalid("invalid_coordinate");
    }

    private static String string(JSONObject value, String key) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof String)) invalid("invalid_string_field");
        return (String) raw;
    }

    private static void fields(JSONObject value, String... expected) {
        Set<String> actual = new HashSet<String>();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) actual.add(keys.next());
        if (!actual.equals(new HashSet<String>(Arrays.asList(expected)))) invalid("unexpected_json_fields");
    }

    private static void invalid(String code) { throw new IllegalArgumentException(code); }
}
