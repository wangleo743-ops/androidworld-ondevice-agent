package com.lab.mobileagent.platform;

import com.lab.mobileagent.l3.ComputerControl;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Host-JVM failure-path checks; injected providers are fixtures, not deployed MobiClaw. */
public final class PlatformContractChecks {
    private static int passed;
    private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;}
    private static Exception denied(Checked work,String name)throws Exception{
        try{work.run();throw new AssertionError("expected_denial:"+name);}catch(Exception expected){passed++;return expected;}
    }
    private static SessionBinding stock()throws Exception{return SessionBinding.stockEmulator("session-1","task-1","Run the stopwatch.","com.google.android.deskclock",10123);}
    private static SessionBinding system()throws Exception{return SessionBinding.systemResolved("system-session","task-1","Run the stopwatch.","trusted-host",10111,"com.google.android.deskclock",10123,4,"framework-ui-6");}
    private static Action tap()throws Exception{return new Action("click",new JSONObject().put("x",20).put("y",30));}
    private static final class Log implements ExecutionLogSink{
        final List<String> events=new ArrayList<>();String failAt;boolean closed;
        public void record(SessionBinding b,String step,String phase,JSONObject details)throws Exception{
            if(phase.equals(failAt))throw new IllegalStateException("fixture_log_failure");events.add(phase);
        }
        public void close(){closed=true;}
    }
    private static final class Device implements ComputerControl{
        int display,executed,closed;boolean closeFails;String observed="com.google.android.deskclock";int lastX;
        public int displayId(){return display;}
        public Observation observe(){return new Observation(new byte[]{1,2,3},100,200,display,observed,observed+"/.Clock");}
        public void execute(Action action)throws Exception{executed++;lastX=action.args.optInt("x",0);}
        public void close(){closed++;if(closeFails)throw new IllegalStateException("fixture_close_failure");}
    }
    private static BaselineSession.Scope scope(){return new BaselineSession.Scope(){
        public void check(String op,Action action){if(!(op.equals("observe")||op.equals("click")))throw new SecurityException("out_of_scope");}
        public void checkObservation(Observation o){if(!"com.google.android.deskclock".equals(o.packageName))throw new SecurityException("wrong_app");}
    };}
    private static void baseline()throws Exception{
        Device device=new Device();Log log=new Log();BaselineSession session=BaselineSession.open(device,stock(),scope(),log);
        session.observe();session.execute(tap());check(device.executed==1,"normal_dispatch");
        check(log.events.indexOf("operation_requested")<log.events.indexOf("operation_returned"),"request_logged_before_feedback");
        check(!session.capabilities().supports(PlatformCapabilities.DISPLAY_LIST),"stock_has_no_display_list");
        denied(()->session.execute("update",null),"stock_update_not_silently_successful");
        session.close();session.close();check(device.closed==1&&log.closed,"close_once");
        denied(()->session.execute(tap()),"closed_session_cannot_dispatch");
        Device changed=new Device();Log changedLog=new Log();BaselineSession wrong=BaselineSession.open(changed,stock(),scope(),changedLog);
        changed.display=1;denied(()->wrong.execute(tap()),"display_change");check(changed.executed==0,"wrong_display_no_input");wrong.close();
        Device outside=new Device();BaselineSession badObs=BaselineSession.open(outside,stock(),scope(),new Log());
        outside.observed="com.example.other";denied(badObs::observe,"wrong_observed_app");denied(()->badObs.execute(tap()),"observation_failure_stops_session");badObs.close();
        Device copy=new Device();BaselineSession immutable=BaselineSession.open(copy,stock(),new BaselineSession.Scope(){
            public void check(String op,Action action)throws Exception{if(action!=null)action.args.put("x",900);}
            public void checkObservation(Observation o){}
        },new Log());immutable.execute(tap());check(copy.lastX==20,"scope_cannot_mutate_command");immutable.close();
        Device blocked=new Device();Log broken=new Log();BaselineSession stop=BaselineSession.open(blocked,stock(),scope(),broken);broken.failAt="operation_requested";
        denied(()->stop.execute(tap()),"log_before_dispatch_failure");check(blocked.executed==0,"log_fail_closed");stop.close();check(blocked.closed==1&&broken.closed,"failed_session_still_released");
        Device failedClose=new Device();Log failedLog=new Log();BaselineSession cleanup=BaselineSession.open(failedClose,stock(),scope(),failedLog);
        failedClose.closeFails=true;failedLog.failAt="closed";Exception original=denied(cleanup::close,"cleanup_failure");
        check(original.getSuppressed().length==1&&failedLog.closed,"cleanup_preserves_primary_and_closes_log");
        check(SessionBinding.stockEmulator("s","t","brightness","com.android.settings",1000).targetUid==1000,"settings_system_uid_supported");
        denied(()->SessionBinding.stockEmulator("s","t","task","com.example.app",1000),"unapproved_system_uid_binding");
        denied(MobiClawPorts::requireSystemImplementation,"mobiclaw_not_installed");
        Path directory=Files.createTempDirectory("platform-log-check-");Path path=directory.resolve("execution.jsonl");
        FileExecutionLog durable=new FileExecutionLog(path.toFile());durable.record(stock(),"step-1","test",new JSONObject().put("result","fixture"));durable.close();
        String first=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);check(first.contains("session-1")&&first.contains("epoch_ms"),"durable_correlated_log");
        denied(()->new FileExecutionLog(path.toFile()),"old_log_cannot_be_overwritten");check(first.equals(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)),"old_log_preserved");
        Files.delete(path);Files.delete(directory);
    }
    private static final class SystemFixture {
        final SessionBinding binding;final Log log=new Log();
        int frames,resolutions,matches,rechecks,queued,released,cancelled,delivered,approvals;
        long now=1000;boolean noGrant,expireInQueue,cancelInQueue,staleTarget,missingCallback,doubleCallback,live=true;
        MobiClawPorts.Recheck outcome=MobiClawPorts.Recheck.VALID;
        AuthorizationRecord original;OperationDescription originalOperation;
        SystemFixture()throws Exception{binding=system();}
        AuthorizedDispatch service(){
            MobiClawPorts.Sessions sessions=new MobiClawPorts.Sessions(){
                public SessionBinding openApproved(String task,String pkg){throw new UnsupportedOperationException("fixture_not_a_real_approval");}
                public void requireLive(SessionBinding b){if(!live||!binding.fingerprint().equals(b.fingerprint()))throw new SecurityException("session_closed_or_changed");}
                public void close(SessionBinding b){live=false;}
            };
            MobiClawPorts.Rendering renderer=new MobiClawPorts.Rendering(){
                public Observation pixels(SessionBinding b){throw new UnsupportedOperationException();}
                public MobiClawPorts.DisplayListFrame displayList(SessionBinding b)throws Exception{frames++;return new MobiClawPorts.DisplayListFrame(b,"obs-1","ui-v1",10,new JSONObject().put("text","Start"));}
                public String requestUpdate(SessionBinding b){throw new UnsupportedOperationException();}
            };
            MobiClawPorts.OperationResolver resolver=(b,id,a,frame)->{resolutions++;originalOperation=new OperationDescription(b,id,frame.observationId,frame.revision,"clock-page","start-button",a);return originalOperation;};
            MobiClawPorts.Authorization authority=new MobiClawPorts.Authorization(){
                public AuthorizationRecord match(OperationDescription op)throws Exception{matches++;if(noGrant)return null;
                    original=new AuthorizationRecord(op,"entry-7",3,"task","task-1",new JSONObject().put("target","start-button"),1000,4000);return original;}
                public void requestApproval(OperationDescription op,String reason){approvals++;}
                public MobiClawPorts.Recheck recheck(AuthorizationRecord receipt){rechecks++;if(receipt!=original)throw new AssertionError("record_reconstructed");return outcome;}
            };
            MobiClawPorts.Resources resources=new MobiClawPorts.Resources(){
                public MobiClawPorts.Admission enqueue(SessionBinding b,OperationDescription command,AuthorizationRecord receipt){queued++;return new MobiClawPorts.Admission("admission-1",command.requestId);}
                public JSONObject whenReady(MobiClawPorts.Admission a,MobiClawPorts.ReadyCallback ready)throws Exception{
                    if(cancelInQueue)throw new java.util.concurrent.CancellationException("cancelled_before_ready");
                    if(expireInQueue)now=5000;if(missingCallback)return new JSONObject();
                    JSONObject feedback=ready.execute(new MobiClawPorts.Reservation("reservation-1",a.id));
                    if(doubleCallback)ready.execute(new MobiClawPorts.Reservation("reservation-1",a.id));return feedback;
                }
                public void cancel(MobiClawPorts.Admission a,String reason){cancelled++;}
                public void release(MobiClawPorts.Reservation r){released++;}
            };
            MobiClawPorts.Commands command=new MobiClawPorts.Commands(){
                public void requireCurrentTarget(SessionBinding b,OperationDescription op){if(staleTarget)throw new SecurityException("ui_revision_changed");}
                public JSONObject deliver(SessionBinding b,OperationDescription op)throws Exception{delivered++;return new JSONObject().put("status","input_delivered");}
            };
            return new AuthorizedDispatch(sessions,renderer,resolver,authority,resources,command,log,()->now);
        }
        JSONObject run()throws Exception{return service().submit(binding,"request-1",tap());}
    }
    private static void systemContracts()throws Exception{
        Action source=tap();OperationDescription op=new OperationDescription(system(),"request-1","obs-1","rev1","page","target",source);
        String hash=op.digest();source.args.put("x",999);op.parameters().put("x",888);
        check(op.parameters().getInt("x")==20&&hash.equals(op.digest()),"operation_snapshot_immutable");
        JSONObject approved=new JSONObject().put("max",10);AuthorizationRecord receipt=new AuthorizationRecord(op,"grant",2,"conversation","conversation-1",approved,100,1000);
        approved.put("max",100);receipt.approvedScope().put("max",999);check(receipt.approvedScope().getInt("max")==10,"authorization_scope_snapshot_immutable");
        OperationDescription other=new OperationDescription(system(),"request-2","obs-1","rev1","page","target",tap());
        denied(()->receipt.requireMatch(other,system(),200),"receipt_not_transferable");
        denied(()->receipt.requireMatch(op,system(),1000),"expiry_boundary");
        check(!new AuthorizationRecord(op,"grant",2,"user","user0",new JSONObject(),100,0).expired(9000),"optional_expiry_contract");
        SystemFixture normal=new SystemFixture();check("input_delivered".equals(normal.run().getString("status")),"authorized_flow");
        check(normal.frames==1&&normal.resolutions==1&&normal.rechecks==1&&normal.delivered==1,"recheck_uses_existing_record");
        check(normal.released==1&&normal.cancelled==0,"completed_reservation_released");
        check(normal.log.events.indexOf("authorization_matched")<normal.log.events.indexOf("resource_queued")
                &&normal.log.events.indexOf("authorization_rechecked")<normal.log.events.indexOf("input_dispatch_started"),"audit_order");
        for(MobiClawPorts.Recheck result:new MobiClawPorts.Recheck[]{MobiClawPorts.Recheck.REVOKED,MobiClawPorts.Recheck.ENTRY_CHANGED,MobiClawPorts.Recheck.SCOPE_ENDED,MobiClawPorts.Recheck.ALREADY_USED,MobiClawPorts.Recheck.UNAVAILABLE}){
            SystemFixture denied=new SystemFixture();denied.outcome=result;denied(denied::run,"store_recheck_"+result);
            check(denied.delivered==0&&denied.released==1&&denied.cancelled==1&&denied.approvals==1,"store_recheck_stops_and_releases_"+result);
        }
        SystemFixture expired=new SystemFixture();expired.expireInQueue=true;denied(expired::run,"expired_in_queue");
        check(expired.delivered==0&&expired.rechecks==0&&expired.approvals==1&&expired.released==1,"local_expiry_before_store_and_input");
        SystemFixture absent=new SystemFixture();absent.noGrant=true;denied(absent::run,"approval_pending");
        check(absent.queued==0&&absent.delivered==0&&absent.approvals==1,"no_grant_never_queued");
        SystemFixture cancelled=new SystemFixture();cancelled.cancelInQueue=true;denied(cancelled::run,"cancelled_before_ready");
        check(cancelled.delivered==0&&cancelled.released==0&&cancelled.cancelled==1,"cancelled_has_no_reservation_or_input");
        SystemFixture stale=new SystemFixture();stale.staleTarget=true;denied(stale::run,"target_stale");
        check(stale.delivered==0&&stale.released==1&&stale.resolutions==1,"stale_target_is_not_reinterpreted");
        SystemFixture missing=new SystemFixture();missing.missingCallback=true;denied(missing::run,"ready_callback_required");check(missing.delivered==0&&missing.cancelled==1,"no_ready_no_input");
        SystemFixture repeated=new SystemFixture();repeated.doubleCallback=true;denied(repeated::run,"ready_callback_single_use");check(repeated.delivered==1&&repeated.released==1,"no_duplicate_input");
        SystemFixture after=new SystemFixture();after.log.failAt="input_returned";denied(after::run,"post_dispatch_log_failure");check(after.delivered==1&&after.released==1&&after.cancelled==1,"uncertain_input_is_not_retried");
        SystemFixture closed=new SystemFixture();closed.live=false;denied(closed::run,"closed_system_session");check(closed.frames==0&&closed.queued==0,"closed_session_no_observation_or_queue");
    }
    public static void main(String[] args)throws Exception{
        baseline();systemContracts();System.out.println(new JSONObject().put("suite","platform_and_reserved_system_contracts")
                .put("passed",passed).put("live_system_backend",false).put("model_calls",0).put("android_execution",false));
    }
}
