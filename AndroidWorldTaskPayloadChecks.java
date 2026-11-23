package com.lab.mobileagent.l1;

import com.lab.mobileagent.benchmark.BenchmarkContract;
import com.lab.mobileagent.model.Observation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure protocol tests. Builds request bytes only; never invokes the HTTP/model path. */
public final class AndroidWorldTaskPayloadChecks {
    private static final String KEY="nonsecret-offline-protocol-placeholder";
    private static int checks;
    private interface Work{void run()throws Exception;}
    private static void require(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void deny(Work work)throws Exception{boolean denied=false;try{work.run();}catch(IllegalStateException expected){denied=true;}require(denied,"unexpected_protocol_allow");}
    private static Observation frame(int display,String pkg){
        ByteBuffer png=ByteBuffer.allocate(24);
        png.put(new byte[]{(byte)137,80,78,71,13,10,26,10});png.putInt(13);png.put(new byte[]{73,72,68,82});png.putInt(2);png.putInt(3);
        return new Observation(png.array(),2,3,display,pkg,pkg+"/.Main",System.nanoTime());
    }
    private static JSONObject payload(ResponsesClient client,String goal,Observation frame)throws Exception{
        return new JSONObject(new String(client.payload(goal,frame,new JSONArray()),StandardCharsets.UTF_8));
    }
    public static void main(String[] unused)throws Exception{
        long deadline=System.nanoTime()+60_000_000_000L;
        String[][] tasks={
            {"ClockStopWatchRunning","com.google.android.deskclock","Run the stopwatch.","start its stopwatch","Do not create an alarm or timer"},
            {"ClockTimerEntry","com.google.android.deskclock","Create a timer with 0 hours, 16 minutes, and 35 seconds. Do not start the timer.","exact timer duration","Do NOT start the timer"},
            {"SystemBrightnessMax","com.android.settings","Turn brightness to the max value.","display brightness to its maximum","change no other setting"},
            {"CameraTakePhoto","com.android.camera2","Take one photo.","exactly one photo","Do not record video, share, delete, or take additional photos"}
        };
        for(String[] task:tasks){
            BenchmarkContract.TaskSpec spec=BenchmarkContract.task(task[0]);
            require(spec.targetPackage.equals(task[1]),"catalog_target_drift");spec.validateGoal(task[2]);checks++;
            ResponsesClient client=ResponsesClient.androidWorldTask(KEY,"test-model","xhigh",deadline,task[0],task[1]);
            Observation observation=frame(0,task[1]);JSONObject body=payload(client,task[2],observation);
            String instructions=body.getString("instructions");
            require(instructions.contains("Task name: "+task[0]),"task_not_bound_in_prompt");
            require(instructions.contains("only target application is "+task[1]),"target_not_bound_in_prompt");
            require(instructions.contains(task[3])&&instructions.contains(task[4]),"task_effect_scope_missing");
            require(instructions.contains("opens ONLY the bound target app "+task[1]),"legacy_open_alias_not_bound");
            require(instructions.contains("exactly kind, action, answer"),"three_field_protocol_missing");
            require(!instructions.contains("type {text")&&!instructions.contains("press_key {key}"),"unsupported_actions_leaked");
            require(!instructions.contains(ResponsesClient.AUTHORIZATION_PROMPT_VERSION)
                    &&!instructions.contains("read-only information retrieval"),"weather_scope_leaked");
            require(body.getBoolean("stream")&&!body.getBoolean("store"),"response_transport_regression");
            require("test-model".equals(body.getString("model"))&&"xhigh".equals(body.getJSONObject("reasoning").getString("effort")),"model_parameters_regression");
            JSONArray input=body.getJSONArray("input");require(input.length()==1,"unexpected_prompt_messages");
            require("user".equals(input.getJSONObject(0).getString("role")),"task_not_user_data");
            JSONArray content=input.getJSONObject(0).getJSONArray("content");
            require(content.length()==2&&"input_text".equals(content.getJSONObject(0).getString("type")),"multimodal_shape_regression");
            require(content.getJSONObject(0).getString("text").contains("USER TASK:\n"+task[2]),"official_goal_changed");
            require("input_image".equals(content.getJSONObject(1).getString("type")),"image_missing");
            String image=content.getJSONObject(1).getString("image_url");
            require(image.startsWith("data:image/png;base64,")&&Arrays.equals(Base64.getDecoder().decode(image.substring(22)),observation.png),"png_transformed");
            require(!body.toString().contains(KEY),"credential_in_json_body");
            deny(()->client.payload(task[2],frame(1,task[1]),new JSONArray()));
            deny(()->ResponsesClient.androidWorldTask(KEY,"test-model","low",deadline,task[0],"com.example.other"));
            if("SystemBrightnessMax".equals(task[0]))require(instructions.contains("system brightness dialog is allowed"),"brightness_dialog_scope_missing");
        }
        deny(()->ResponsesClient.androidWorldTask(KEY,"test-model","low",deadline,"UnknownTask","com.google.android.deskclock"));
        deny(()->ResponsesClient.androidWorldTask(KEY,"test-model","low",deadline,"SystemBrightnessMin","com.android.settings"));
        deny(()->ResponsesClient.androidWorldTask(KEY,"test-model","low",deadline,"ClockStopWatchPausedVerify","com.google.android.deskclock"));
        deny(()->ResponsesClient.androidWorldTask(KEY,"test-model","low",deadline,"CameraTakePhoto","com.android.settings"));

        ResponsesClient weatherDefault=new ResponsesClient(KEY,"test-model","low",deadline);
        String defaultInstructions=payload(weatherDefault,"Check Beijing weather",frame(1,"com.android.browser")).getString("instructions");
        require(defaultInstructions.contains("read-only information retrieval"),"default_weather_changed");
        require(defaultInstructions.contains("type {text,press_enter}")&&defaultInstructions.contains("press_key {key}"),"default_weather_action_regression");
        require(!defaultInstructions.contains("Task name:")&&!defaultInstructions.contains("AndroidWorld benchmark"),"benchmark_scope_leaked_to_weather");
        deny(()->weatherDefault.payload("weather",frame(0,"com.android.browser"),new JSONArray()));
        ResponsesClient weatherAuthorized=new ResponsesClient(KEY,"test-model","low",deadline,true);
        String authorized=payload(weatherAuthorized,"Check Beijing weather",frame(1,"com.android.browser")).getString("instructions");
        require(authorized.contains(ResponsesClient.AUTHORIZATION_PROMPT_VERSION)&&authorized.contains("exactly four keys: kind, action, answer, authorization"),"weather_authorization_regression");
        deny(()->weatherAuthorized.payload("weather",frame(0,"com.android.browser"),new JSONArray()));

        ResponsesClient legacy=ResponsesClient.androidWorldClock(KEY,"test-model","low",deadline);
        String legacyPrompt=payload(legacy,"Run the stopwatch.",frame(0,"com.google.android.deskclock")).getString("instructions");
        require(legacyPrompt.equals(ResponsesClient.androidWorldClockInstructions()),"legacy_clock_factory_changed");
        require(legacyPrompt.contains("ONLY Google Clock")&&!legacyPrompt.contains("Task name: CameraTakePhoto"),"legacy_clock_scope_changed");
        deny(()->legacy.payload("Run the stopwatch.",frame(1,"com.google.android.deskclock"),new JSONArray()));
        System.out.println(new JSONObject().put("passed",checks).put("network_requests",0).put("model_calls",0).put("benchmark_executed",false));
    }
}
