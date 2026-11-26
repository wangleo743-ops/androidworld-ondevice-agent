package com.lab.mobileagent.l1;

import com.lab.mobileagent.model.Observation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure payload regression: no HTTP request and no real screen/key. */
public final class AndroidWorldPayloadChecks {
    private interface Work{void run()throws Exception;}
    private static int checks;
    private static void require(boolean b){if(!b)throw new AssertionError("payload_regression");checks++;}
    private static void deny(Work call)throws Exception{boolean denied=false;try{call.run();}catch(IllegalStateException expected){denied=true;}require(denied);}
    private static Observation frame(int display){
        ByteBuffer bytes=ByteBuffer.allocate(24);
        bytes.put(new byte[]{(byte)137,80,78,71,13,10,26,10});bytes.putInt(13);bytes.put(new byte[]{73,72,68,82});bytes.putInt(2);bytes.putInt(3);
        return new Observation(bytes.array(),2,3,display,"com.google.android.deskclock","clock",System.nanoTime());
    }
    public static void main(String[] ignored)throws Exception{
        long deadline=System.nanoTime()+30_000_000_000L;
        ResponsesClient benchmark=ResponsesClient.androidWorldClock("nonsecret-placeholder","test-model","low",deadline);
        ResponsesClient weather=new ResponsesClient("nonsecret-placeholder","test-model","low",deadline,true);
        JSONObject b=new JSONObject(new String(benchmark.payload("Run the stopwatch.",frame(0),new JSONArray()),StandardCharsets.UTF_8));
        require(b.getString("model").equals("test-model"));require(!b.getBoolean("store"));require(b.getBoolean("stream"));
        require(b.getString("instructions").contains("ClockStopWatchRunning"));
        require(b.getString("instructions").contains("ONLY Google Clock"));
        require(!b.getString("instructions").contains("beijing-weather-authorization-v1"));
        require(!b.getString("instructions").contains("read-only information retrieval"));
        deny(()->weather.payload("weather",frame(0),new JSONArray()));
        deny(()->benchmark.payload("Run the stopwatch.",frame(1),new JSONArray()));
        JSONObject w=new JSONObject(new String(weather.payload("weather",frame(1),new JSONArray()),StandardCharsets.UTF_8));
        require(w.getString("instructions").contains("beijing-weather-authorization-v1"));
        require(w.getString("instructions").contains("read-only information retrieval"));
        require(!w.getString("instructions").contains("ClockStopWatchRunning"));
        deny(()->benchmark.payload("Run the stopwatch.",new Observation(new byte[24],2,3,0,"clock","clock"),new JSONArray()));
        System.out.println(new JSONObject().put("passed",checks).put("network_requests",0).put("benchmark_score_claimed",false));
    }
}
