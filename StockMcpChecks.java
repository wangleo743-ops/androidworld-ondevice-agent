package com.lab.mobileagent.standard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real host process pipes verify the same transport used by the Android proxy. */
public final class StockMcpChecks {
    static int checks;
    static void require(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    interface Attempt {void run()throws Exception;}
    static void denied(Attempt attempt,String name)throws Exception{
        boolean failed=false;try{attempt.run();}catch(Exception expected){failed=true;}require(failed,name);
    }
    static Process child(String mode)throws Exception{
        File java=new File(System.getProperty("java.home"),"bin/java"+(System.getProperty("os.name").startsWith("Windows")?".exe":""));
        return new ProcessBuilder(java.getPath(),"-cp",System.getProperty("java.class.path"),StockMcpFixture.class.getName(),mode).start();
    }
    static JSONObject bootstrap()throws Exception{return new JSONObject().put("profile",StockBootstrap.PROFILE)
        .put("run_id","aw-host-fixture").put("session_id",UUID.randomUUID().toString()).put("task_name","ClockStopWatchRunning")
        .put("target_package","com.google.android.deskclock").put("goal","Run the stopwatch.").put("client_pid",1234).put("lifetime_ms",30000);}
    public static void main(String[] args)throws Exception{
        JSONObject boot=bootstrap();require(StockBootstrap.parse(boot).task.targetPackage.equals("com.google.android.deskclock"),"trusted_target");
        denied(()->StockBootstrap.parse(new JSONObject(boot.toString()).put("api_key","fixture")),"credential_bootstrap_refused");
        denied(()->StockBootstrap.parse(new JSONObject(boot.toString()).put("target_package","com.android.settings")),"target_substitution_refused");
        denied(()->StockBootstrap.parse(new JSONObject(boot.toString()).put("goal","Do anything.")),"goal_substitution_refused");
        denied(()->StockBootstrap.parse(new JSONObject(boot.toString()).put("display_id",1)),"display_selection_refused");
        String parent="/system/bin/app_process\0/system/bin\0com.lab.mobileagent.benchmark.AndroidWorldMain\0run\0aw-host-fixture\0";
        require(StockBootstrap.parentCommandMatches(parent.getBytes(StandardCharsets.UTF_8),"aw-host-fixture"),"exact_parent_command");
        require(!StockBootstrap.parentCommandMatches(parent.replace("aw-host-fixture","aw-host-fixture-extra").getBytes(StandardCharsets.UTF_8),"aw-host-fixture"),"parent_run_substring_refused");
        require(!StockBootstrap.parentCommandMatches(parent.replace("AndroidWorldMain","AndroidWorldMainEvil").getBytes(StandardCharsets.UTF_8),"aw-host-fixture"),"parent_class_substring_refused");
        require(!StockBootstrap.parentCommandMatches(parent.replace("\0run\0","\0launch\0").getBytes(StandardCharsets.UTF_8),"aw-host-fixture"),"parent_operation_mismatch");
        denied(()->StockWire.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,10}),8),"invalid_utf8");
        denied(()->StockWire.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),8),"partial_frame");
        denied(()->StockWire.read(new ByteArrayInputStream("123456789\n".getBytes(StandardCharsets.UTF_8)),8),"frame_size_limit");
        Process normal=child("normal");StockMcpConnection connection=new StockMcpConnection(normal,boot,System.nanoTime()+TimeUnit.SECONDS.toNanos(20),3000);
        require(connection.callTool("control.session",new JSONObject()).getJSONObject("structuredContent").getBoolean("fixture"),"cross_process_roundtrip");
        connection.close();JSONObject report=connection.report();
        require(report.getBoolean("closed")&&report.getBoolean("child_exit_confirmed")&&!normal.isAlive(),"graceful_process_exit");
        require(report.getJSONObject("final_server_report").getBoolean("cleanup_confirmed"),"final_cleanup_receipt");
        denied(()->connection.callTool("gui.observe",new JSONObject()),"call_after_close");
        for(String mode:new String[]{"id","version","malformed","partial","oversize","eof","timeout"}){
            Process bad=child(mode);long started=System.nanoTime();
            denied(()->new StockMcpConnection(bad,bootstrap(),System.nanoTime()+TimeUnit.SECONDS.toNanos(5),700),"handshake_"+mode);
            require(bad.waitFor(3,TimeUnit.SECONDS)&&!bad.isAlive(),"failed_child_reaped_"+mode);
            require(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(8),"failure_bounded_"+mode);
        }
        for(String mode:new String[]{"tool-eof","tool-timeout","tool-shape"}){
            Process bad=child(mode);StockMcpConnection c=new StockMcpConnection(bad,bootstrap(),System.nanoTime()+TimeUnit.SECONDS.toNanos(10),700);
            denied(()->c.callTool("gui.observe",new JSONObject()),"tool_"+mode);
            require(bad.waitFor(3,TimeUnit.SECONDS)&&!bad.isAlive(),"tool_child_reaped_"+mode);
            c.close();require(c.report().getBoolean("child_exit_confirmed"),"tool_exit_report_"+mode);
            require(c.report().isNull("final_server_report"),"abort_not_clean_server_receipt_"+mode);
        }
        for(String mode:new String[]{"tool-error","tool-error-unsafe"}){
            Process rejected=child(mode);StockMcpConnection c=new StockMcpConnection(rejected,bootstrap(),System.nanoTime()+TimeUnit.SECONDS.toNanos(10),1500);
            String expected="tool-error".equals(mode)?"benchmark_foreground_out_of_scope":"stock_tool_execution_rejected";
            boolean typed=false;
            try{c.callTool("gui.click",new JSONObject().put("x",294).put("y",169));}
            catch(StockMcpConnection.ToolRejectedException failure){typed=expected.equals(failure.getMessage());}
            require(typed,"valid_tool_error_is_typed_and_safe_"+mode);
            require(rejected.isAlive()&&!c.report().getBoolean("closed"),"valid_error_keeps_cleanup_pipe_"+mode);
            require(c.report().getBoolean("execution_rejected"),"valid_error_execution_disabled_"+mode);
            JSONObject refusal=c.report().getJSONObject("last_tool_error");
            require(expected.equals(refusal.getString("code"))&&"gui.click".equals(refusal.getString("tool"))
                    &&"stock-2".equals(refusal.getString("request_id")),"safe_error_binding_recorded_"+mode);
            int requests=c.report().getInt("requests");
            denied(()->c.callTool("gui.click",new JSONObject().put("x",1).put("y",2)),"action_after_refusal_"+mode);
            denied(()->c.callTool("gui.observe",new JSONObject()),"observe_after_refusal_"+mode);
            denied(()->c.callTool("control.session",new JSONObject()),"session_after_refusal_"+mode);
            denied(()->c.request("ping",new JSONObject()),"raw_request_after_refusal_"+mode);
            denied(()->c.request("tools/call",new JSONObject().put("name","gui.click").put("arguments",new JSONObject())),"raw_action_after_refusal_"+mode);
            denied(()->c.request("tools/call",new JSONObject().put("name","control.close")
                    .put("arguments",new JSONObject().put("unexpected",true))),"close_arguments_after_refusal_"+mode);
            require(c.report().getInt("requests")==requests&&rejected.isAlive(),"refused_requests_not_sent_or_killed_"+mode);
            c.close();JSONObject finalReceipt=c.report();
            require(finalReceipt.getBoolean("child_exit_confirmed")&&!rejected.isAlive(),"rejected_session_process_reaped_"+mode);
            require(finalReceipt.getJSONObject("final_server_report").getBoolean("cleanup_confirmed")
                    &&finalReceipt.getJSONObject("final_server_report").getInt("tool_calls_seen")==2,"only_error_then_close_reached_server_"+mode);
            require(expected.equals(finalReceipt.getJSONObject("last_tool_error").getString("code")),"error_survives_close_"+mode);
            require(!finalReceipt.toString().contains("private diagnostic"),"server_text_not_persisted_"+mode);
        }
        for(String mode:new String[]{"tool-error-close-eof","tool-error-close-timeout"}){
            Process rejected=child(mode);StockMcpConnection c=new StockMcpConnection(rejected,bootstrap(),System.nanoTime()+TimeUnit.SECONDS.toNanos(10),700);
            denied(()->c.callTool("gui.observe",new JSONObject()),"refusal_before_failed_close_"+mode);
            denied(()->c.close(),"failed_close_reported_"+mode);
            require(c.report().getBoolean("child_exit_confirmed")&&!rejected.isAlive(),"failed_close_child_reaped_"+mode);
            require(c.report().isNull("final_server_report"),"failed_close_not_claimed_clean_"+mode);
            require("benchmark_foreground_out_of_scope".equals(c.report().getJSONObject("last_tool_error").getString("code")),"original_refusal_survives_close_failure_"+mode);
        }
        System.out.println(new JSONObject().put("suite","stock_mcp_host_process_transport").put("passed",checks).put("success",true)
                .put("android_runtime_tested",false).put("model_calls",0).toString());
    }
}
