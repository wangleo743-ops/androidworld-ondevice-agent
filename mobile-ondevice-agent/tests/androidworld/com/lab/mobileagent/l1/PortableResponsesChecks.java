package com.lab.mobileagent.l1;

import com.lab.mobileagent.model.Observation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local loopback HTTP fixture only: no real API, user data, model or Android device. */
public final class PortableResponsesChecks {
    private static int checks;
    private static final String KEY = "nonsecret-test-placeholder";
    private interface Work { void run() throws Exception; }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    private static void denied(Work work) throws Exception {
        try { work.run(); throw new AssertionError("unexpected_allow"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static Observation frame() {
        ByteBuffer data=ByteBuffer.allocate(24);
        data.put(new byte[]{(byte)137,80,78,71,13,10,26,10}).putInt(13)
                .put(new byte[]{73,72,68,82}).putInt(2).putInt(3);
        return new Observation(data.array(),2,3,0,"com.google.android.deskclock",
                "com.google.android.deskclock/.DeskClock",System.nanoTime());
    }
    private static ResponsesClient client(String endpoint,String effort) {
        return ResponsesClient.androidWorldTask(KEY,"configured-vision-model",effort,
                System.nanoTime()+30_000_000_000L,"ClockStopWatchRunning","com.google.android.deskclock",endpoint,true);
    }
    public static void main(String[] unused) throws Exception {
        check(ModelConnection.DEFAULT_ENDPOINT.equals(ModelConnection.endpoint(ModelConnection.DEFAULT_ENDPOINT,false)),"default_https");
        check("http://127.0.0.1:8080/responses".equals(ModelConnection.endpoint("http://127.0.0.1:8080/responses",true)),"explicit_http");
        for(String endpoint:new String[]{"http://127.0.0.1:8080/responses","https://user:password@example.test/responses",
                "https://example.test/responses?key=forbidden","https://example.test/responses#secret",
                "file:///tmp/responses","https://example.test/chat/completions","https://example.test:0/responses",
                "https://example.test:65536/responses","https://example.test/responses\n","https:///responses"})
            denied(()->ModelConnection.endpoint(endpoint,false));
        check(!ModelConnection.validEffort(null)&&!ModelConnection.validEffort("unbounded"),"effort_validation");
        JSONObject payload=new JSONObject(new String(client("http://127.0.0.1:8080/responses","default")
                .payload("Run the stopwatch.",frame(),new JSONArray()),StandardCharsets.UTF_8));
        check(!payload.has("reasoning"),"default_omits_reasoning");
        check("configured-vision-model".equals(payload.getString("model")),"configured_model");
        for(String effort:new String[]{"none","minimal","low","medium","high","xhigh"}) {
            JSONObject body=new JSONObject(new String(client("http://127.0.0.1:8080/responses",effort)
                    .payload("Run the stopwatch.",frame(),new JSONArray()),StandardCharsets.UTF_8));
            check(effort.equals(body.getJSONObject("reasoning").getString("effort")),"effort_forwarded");
        }
        AtomicReference<JSONObject> received=new AtomicReference<>();
        AtomicReference<String> header=new AtomicReference<>();
        AtomicInteger destinationCalls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/custom/responses",exchange->{
            try {
                header.set(exchange.getRequestHeaders().getFirst("Authorization"));
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[4096];int n;
                while((n=exchange.getRequestBody().read(buffer))!=-1)bytes.write(buffer,0,n);
                received.set(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));
                JSONObject decision=new JSONObject().put("kind","finish").put("action",JSONObject.NULL).put("answer","fixture result");
                JSONObject content=new JSONObject().put("type","output_text").put("text",decision.toString());
                JSONObject message=new JSONObject().put("type","message").put("role","assistant").put("status","completed")
                        .put("content",new JSONArray().put(content));
                JSONObject response=new JSONObject().put("status","completed").put("error",JSONObject.NULL)
                        .put("output",new JSONArray().put(message));
                JSONObject event=new JSONObject().put("type","response.completed").put("response",response);
                byte[] output=("event: response.completed\ndata: "+event+"\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);
            } catch(Exception failure) {throw new java.io.IOException(failure);}
            finally {exchange.close();}
        });
        server.createContext("/redirect/responses",exchange->{
            exchange.getResponseHeaders().set("Location","/redirected/responses");
            exchange.sendResponseHeaders(307,-1);exchange.close();
        });
        server.createContext("/redirected/responses",exchange->{
            destinationCalls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            Decision decision=client(base+"/custom/responses","default").decide("Run the stopwatch.",frame(),new JSONArray());
            check("finish".equals(decision.kind),"custom_endpoint_used_and_sse_parsed");
            check(("Bearer "+KEY).equals(header.get()),"authorization_header");
            check(received.get()!=null&&!received.get().toString().contains(KEY),"key_not_in_request_json");
            check(!received.get().has("reasoning"),"wire_default_omits_reasoning");
            try {client(base+"/redirect/responses","default").decide("Run the stopwatch.",frame(),new JSONArray());
                throw new AssertionError("redirect_accepted");}
            catch(IllegalStateException expected){check("model_http_307".equals(expected.getMessage()),"redirect_refused");}
            check(destinationCalls.get()==0,"credential_not_forwarded_to_redirect");
        } finally {server.stop(0);}
        System.out.println(new JSONObject().put("passed",checks).put("real_model_calls",0)
                .put("android_device_actions",0).put("network","loopback_fixture_only"));
    }
}
