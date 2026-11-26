package com.lab.mobileagent.l1;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real completed-SSE parsing, entirely in memory; no HTTP, model calls or device access. */
public final class AndroidWorldDecisionDiagnosticChecks {
    private static int checks;
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static BufferedReader sse(String decision) throws Exception {
        JSONObject part = new JSONObject().put("type", "output_text").put("text", decision);
        JSONObject message = new JSONObject().put("type", "message").put("role", "assistant")
                .put("status", "completed").put("content", new JSONArray().put(part));
        JSONObject response = new JSONObject().put("status", "completed").put("error", JSONObject.NULL)
                .put("output", new JSONArray().put(message));
        JSONObject event = new JSONObject().put("type", "response.completed").put("response", response);
        return new BufferedReader(new StringReader("event: response.completed\ndata: " + event + "\n\n"));
    }
    private static Decision parse(ResponsesClient client, String decision) throws Exception {
        return client.readModelResponse(sse(decision), System.nanoTime() + 30_000_000_000L, new AtomicBoolean());
    }
    private static void deny(ResponsesClient client, String decision, String code) throws Exception {
        try { parse(client, decision); throw new AssertionError("invalid_decision_was_allowed"); }
        catch (IllegalStateException expected) {
            require(code.equals(expected.getMessage()), "unexpected_safe_code:" + expected.getMessage());
            require(expected.getMessage().matches("model_[a-z0-9_]{1,60}"), "provider_code_filter_incompatible");
        }
    }
    private static String action(String name, String args) {
        return "{\"kind\":\"action\",\"action\":{\"name\":\"" + name + "\",\"args\":" + args + "},\"answer\":null}";
    }
    public static void main(String[] unused) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        ResponsesClient benchmark = ResponsesClient.androidWorldTask("offline-placeholder", "test-model", "xhigh",
                deadline, "SystemBrightnessMax", "com.android.settings");
        ResponsesClient oldClock = ResponsesClient.androidWorldClock("offline-placeholder", "test-model", "xhigh", deadline);
        ResponsesClient weather = new ResponsesClient("offline-placeholder", "test-model", "xhigh", deadline);
        ResponsesClient authorizedWeather = new ResponsesClient("offline-placeholder", "test-model", "xhigh", deadline, true);
        String click = action("click", "{\"x\":1,\"y\":999}");
        require(parse(benchmark, click).action.name.equals("click"), "valid_action_changed");
        require(parse(weather, click).action.name.equals("click"), "weather_action_changed");
        require(parse(benchmark, "{\"kind\":\"finish\",\"action\":null,\"answer\":\"done\"}").kind.equals("finish"), "valid_finish_changed");
        String[][] cases = {
            {action("click", "{\"x\":1000,\"y\":0}"), "invalid_coordinate"},
            {action("click", "{\"x\":1.5,\"y\":0}"), "invalid_coordinate"},
            {action("click", "{\"x\":0,\"y\":0,\"secret\":\"untrusted-placeholder\"}"), "unexpected_json_fields"},
            {click.substring(0, click.length()-1) + ",\"extra\":true}", "unexpected_json_fields"},
            {action("click", "{\"x\":0}"), "unexpected_json_fields"},
            {action("tap", "{\"x\":0,\"y\":0}"), "unsupported_action"},
            {action("open_app", "{\"app_name\":\"Settings\"}"), "app_not_allowed"},
            {action("wait", "{\"seconds\":11}"), "invalid_wait"},
            {"{\"kind\":\"finish\",\"action\":null,\"answer\":\"\"}", "invalid_final_decision"},
            {"{\"kind\":17,\"action\":null,\"answer\":null}", "invalid_string_field"},
            {"{\"kind\":\"action\",\"action\":null,\"answer\":null}", "invalid_action"},
            {"```json\n" + click + "\n```", "invalid_json"},
            {click + " untrusted-placeholder", "invalid_json_trailing_data"},
            {"{\"kind\":\"finish\",\"kind\":\"blocked\",\"action\":null,\"answer\":\"done\"}", "duplicate_json_key"},
            {"[]", "invalid_json_object"}
        };
        for (String[] test : cases) {
            deny(benchmark, test[0], "model_invalid_decision_" + test[1]);
            deny(weather, test[0], "model_invalid_decision");
        }
        deny(oldClock, action("click", "{\"x\":-1,\"y\":0}"), "model_invalid_decision_invalid_coordinate");
        deny(authorizedWeather, click, "model_invalid_decision");
        try {
            ResponsesClient.readSse(sse(action("click", "{\"x\":-1,\"y\":0}")), deadline, new AtomicBoolean(), false);
            throw new AssertionError("legacy_sse_must_reject");
        } catch (IllegalStateException expected) { require(expected.getMessage().equals("model_invalid_decision"), "legacy_sse_contract_changed"); }
        require(ResponsesClient.decisionFailureCode(new IllegalArgumentException("secret-untrusted-placeholder"), true)
                .equals("model_invalid_decision"), "arbitrary_message_leaked");
        require(ResponsesClient.decisionFailureCode(new IllegalStateException("invalid_coordinate"), true)
                .equals("model_invalid_decision"), "untrusted_exception_type_forwarded");
        require(ResponsesClient.decisionFailureCode(new IllegalArgumentException(), true)
                .equals("model_invalid_decision"), "null_message_forwarded");
        require(ResponsesClient.decisionFailureCode(new IllegalArgumentException("invalid_coordinate"), false)
                .equals("model_invalid_decision"), "weather_details_enabled");
        System.out.println("AndroidWorldDecisionDiagnosticChecks passed=" + checks);
    }
}
