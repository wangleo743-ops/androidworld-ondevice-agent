package com.lab.mobileagent.platform;

import com.lab.mobileagent.l3.ComputerControl;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import java.util.Arrays;
import org.json.JSONObject;

/**
 * Standard-Android task connection and evidence, wrapping a concrete Control backend.
 * No system grant, DisplayList, resource scheduling or UI reconstruction is simulated here.
 * This wrapper has no queue; the MCP service owns request admission and dispatch ordering.
 */
public final class BaselineSession implements ComputerControl {
    public interface Scope {
        void check(String operation,Action action)throws Exception;
        void checkObservation(Observation observation)throws Exception;
    }
    private final ComputerControl control;
    private final SessionBinding binding;
    private final Scope scope;
    private final ExecutionLogSink log;
    private final PlatformCapabilities capabilities=PlatformCapabilities.stockAndroid();
    private long sequence;
    private int actions,observations;
    private boolean failed,closed,released;

    private BaselineSession(ComputerControl control,SessionBinding binding,Scope scope,ExecutionLogSink log){
        this.control=control;this.binding=binding;this.scope=scope;this.log=log;
    }
    public static BaselineSession open(ComputerControl control,SessionBinding binding,Scope scope,ExecutionLogSink log)throws Exception{
        if(control==null||binding==null||scope==null||log==null)throw new IllegalArgumentException("baseline_session_dependencies_required");
        try{
            if(!"dedicated_emulator_scope".equals(binding.bindingKind)||binding.displayId!=0
                    ||binding.callerUid!=2000||control.displayId()!=binding.displayId)
                throw new SecurityException("baseline_session_binding_mismatch");
            BaselineSession session=new BaselineSession(control,binding,scope,log);
            log.record(binding,"session","opened",new JSONObject().put("binding",binding.json())
                    .put("capabilities",session.capabilities.json()).put("authorization","operator_owned_emulator_scope_only"));
            return session;
        }catch(Exception failure){
            try{control.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try{log.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private void active(){
        if(closed||failed)throw new IllegalStateException("baseline_session_not_active");
        if(control.displayId()!=binding.displayId){failed=true;throw new SecurityException("baseline_display_binding_changed");}
    }
    private String next(){return "control-"+(++sequence);}
    private void fail(String step,Exception failure)throws Exception{
        failed=true;
        try{log.record(binding,step,"failed",new JSONObject().put("error_type",failure.getClass().getSimpleName())
                .put("error_code",com.lab.mobileagent.runtime.ControlErrors.safe(failure))
                .put("execution_state","may_have_been_dispatched_inspect_evidence_do_not_blindly_retry"));}
        catch(Exception logging){failure.addSuppressed(logging);}
        throw failure;
    }
    @Override public int displayId(){return binding.displayId;}
    public SessionBinding binding(){return binding;}
    public PlatformCapabilities capabilities(){return capabilities;}

    @Override public synchronized Observation observe()throws Exception{
        active();String step=next();long started=System.nanoTime();
        try{
            scope.check("observe",null);
            log.record(binding,step,"observation_requested",new JSONObject().put("format","pixels"));
            Observation image=control.observe();
            if(image==null||image.displayId!=binding.displayId||image.png==null||image.png.length==0
                    ||image.width<=0||image.height<=0)throw new SecurityException("baseline_invalid_observation_binding");
            scope.checkObservation(image);active();
            observations++;
            String imageHash=hexDigest(image.png);
            log.record(binding,step,"observation_returned",new JSONObject().put("package",image.packageName)
                    .put("component",image.component).put("display_id",image.displayId).put("width",image.width)
                    .put("height",image.height).put("png_sha256",imageHash).put("captured_ns",image.capturedNanos)
                    .put("action_boundary_ns",image.actionBoundaryNanos).put("observed_ns",image.observedNanos)
                    .put("frame_after_action",image.frameAfterAction).put("wall_ns",System.nanoTime()-started)
                    .put("framework_state_export","unsupported"));
            return image;
        }catch(Exception failure){fail(step,failure);return null;}
    }
    private static String hexDigest(byte[] bytes)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return text.toString();
    }
    @Override public synchronized void execute(Action proposed)throws Exception{
        active();String step=next();long started=System.nanoTime();
        try{
            if(proposed==null)throw new IllegalArgumentException("baseline_action_required");
            Action frozen=new Action(proposed.name,proposed.args);
            if(!Arrays.asList("open_app","click","swipe","type","press_key","go_back","wait","take_screenshot").contains(frozen.name))
                throw new UnsupportedOperationException("baseline_operation_unsupported");
            // Give validation its own copy. A validator cannot change the admitted command.
            scope.check(frozen.name,new Action(frozen.name,frozen.args));
            log.record(binding,step,"operation_requested",new JSONObject().put("action",frozen.json())
                    .put("authorization","operator_scope_not_system_grant"));
            active();
            control.execute(frozen);actions++;
            active();
            log.record(binding,step,"operation_returned",new JSONObject().put("operation",frozen.name)
                    .put("wall_ns",System.nanoTime()-started).put("status","control_returned_not_business_success"));
        }catch(Exception failure){fail(step,failure);}
    }
    public synchronized JSONObject execute(String operation,Action action)throws Exception{
        if("update".equals(operation)){capabilities.require(PlatformCapabilities.UPDATE);}
        if("observe".equals(operation)&&action==null){
            Observation observation=observe();
            return new JSONObject().put("display_id",observation.displayId).put("package",observation.packageName)
                    .put("width",observation.width).put("height",observation.height).put("format","pixels");
        }
        if(action==null||!operation.equals(action.name))throw new IllegalArgumentException("operation_action_mismatch");
        execute(action);
        return new JSONObject().put("status","control_returned").put("display_id",binding.displayId)
                .put("target_package",binding.targetPackage);
    }
    public synchronized JSONObject report()throws Exception{
        return new JSONObject().put("schema","baseline_task_session_v1").put("binding",binding.json())
                .put("capabilities",capabilities.json()).put("closed",closed).put("failed",failed)
                .put("control_released",released).put("actions",actions).put("observations",observations)
                .put("formal_user_grants",false).put("logging_location","control_service_process");
    }
    @Override public synchronized void close()throws Exception{
        if(closed){if(!released)throw new IllegalStateException("baseline_cleanup_unconfirmed");return;}
        closed=true;Exception failure=null;
        try{control.close();released=true;}catch(Exception cleanup){failed=true;failure=cleanup;}
        try{log.record(binding,"session","closed",new JSONObject().put("control_released",released).put("failed",failed));}
        catch(Exception logging){failed=true;if(failure==null)failure=logging;else failure.addSuppressed(logging);}
        try{log.close();}catch(Exception logging){failed=true;if(failure==null)failure=logging;else failure.addSuppressed(logging);}
        if(failure!=null)throw failure;
    }
}
