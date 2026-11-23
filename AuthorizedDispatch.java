package com.lab.mobileagent.platform;

import com.lab.mobileagent.model.Action;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Paper 4.2 admission/recheck contract for a future trusted system adapter.
 * Not wired to the stock baseline and not evidence of a deployed system authorization service.
 * The store, Android identity checks, DisplayList resolver and kernel resource implementation are ports.
 */
public final class AuthorizedDispatch {
    public static final class ApprovalRequired extends Exception {
        public final String reason;
        public ApprovalRequired(String reason){super("reauthorization_required:"+reason);this.reason=reason;}
    }
    private final MobiClawPorts.Sessions sessions;
    private final MobiClawPorts.Rendering rendering;
    private final MobiClawPorts.OperationResolver resolver;
    private final MobiClawPorts.Authorization authorization;
    private final MobiClawPorts.Resources resources;
    private final MobiClawPorts.Commands commands;
    private final ExecutionLogSink log;
    private final MobiClawPorts.Clock clock;
    public AuthorizedDispatch(MobiClawPorts.Sessions sessions,MobiClawPorts.Rendering rendering,
            MobiClawPorts.OperationResolver resolver,MobiClawPorts.Authorization authorization,
            MobiClawPorts.Resources resources,MobiClawPorts.Commands commands,ExecutionLogSink log,MobiClawPorts.Clock clock){
        if(sessions==null||rendering==null||resolver==null||authorization==null||resources==null||commands==null||log==null||clock==null)
            throw new IllegalArgumentException("trusted_system_ports_required");
        this.sessions=sessions;this.rendering=rendering;this.resolver=resolver;this.authorization=authorization;
        this.resources=resources;this.commands=commands;this.log=log;this.clock=clock;
    }
    public JSONObject submit(SessionBinding binding,String requestId,Action requested)throws Exception{
        sessions.requireLive(binding);
        if(requested==null)throw new IllegalArgumentException("operation_required");
        final Action frozen=new Action(requested.name,requested.args);
        final MobiClawPorts.DisplayListFrame frame=rendering.displayList(binding);
        if(frame==null||!binding.fingerprint().equals(frame.bindingHash))throw new SecurityException("display_list_session_mismatch");
        final OperationDescription operation=resolver.resolve(binding,requestId,new Action(frozen.name,frozen.args),frame);
        if(operation==null)throw new IllegalStateException("operation_resolution_unavailable");
        operation.requireBinding(binding);
        if(!requestId.equals(operation.requestId)||!frame.observationId.equals(operation.observationId)
                ||!frame.revision.equals(operation.uiRevision)||!frozen.name.equals(operation.actionName)
                ||!JsonSnapshot.object(frozen.args).equals(JsonSnapshot.object(operation.parameters())))
            throw new SecurityException("resolved_operation_mismatch");
        log.record(binding,requestId,"operation_resolved",operation.json());
        final AuthorizationRecord record=authorization.match(operation);
        if(record==null){requestApproval(binding,operation,"no_matching_grant");throw new ApprovalRequired("no_matching_grant");}
        try{record.requireMatch(operation,binding,clock.epochMillis());}
        catch(SecurityException invalid){requestApproval(binding,operation,"invalid_or_expired_match");throw invalid;}
        log.record(binding,requestId,"authorization_matched",record.json());
        sessions.requireLive(binding);
        final MobiClawPorts.Admission admission=resources.enqueue(binding,operation,record);
        if(admission==null)throw new IllegalStateException("resource_admission_missing");
        final AtomicBoolean invoked=new AtomicBoolean();
        try{
            if(!requestId.equals(admission.requestId))throw new IllegalStateException("resource_admission_mismatch");
            log.record(binding,requestId,"resource_queued",new JSONObject().put("admission_id",admission.id));
            JSONObject result=resources.whenReady(admission,reservation->{
                if(!invoked.compareAndSet(false,true))throw new IllegalStateException("resource_callback_reused");
                if(reservation==null||!admission.id.equals(reservation.admissionId))
                    throw new SecurityException("resource_reservation_mismatch");
                Exception original=null;
                try{
                    sessions.requireLive(binding);
                    try{record.requireMatch(operation,binding,clock.epochMillis());}
                    catch(SecurityException expired){requestApproval(binding,operation,"expired_before_dispatch");throw new ApprovalRequired("expired_before_dispatch");}
                    MobiClawPorts.Recheck outcome=authorization.recheck(record);
                    log.record(binding,requestId,"authorization_rechecked",new JSONObject().put("entry_id",record.entryId)
                            .put("entry_version",record.entryVersion).put("outcome",outcome==null?"UNAVAILABLE":outcome.name()));
                    if(outcome!=MobiClawPorts.Recheck.VALID){
                        String reason=outcome==null?"UNAVAILABLE":outcome.name();
                        requestApproval(binding,operation,reason);throw new ApprovalRequired(reason);
                    }
                    // Frame/target staleness is independent of the store recheck. Never rebuild the description here.
                    commands.requireCurrentTarget(binding,operation);
                    sessions.requireLive(binding);
                    log.record(binding,requestId,"input_dispatch_started",new JSONObject().put("operation_digest",operation.digest())
                            .put("reservation_id",reservation.id));
                    JSONObject feedback=commands.deliver(binding,operation);
                    if(feedback==null)throw new IllegalStateException("framework_feedback_missing");
                    log.record(binding,requestId,"input_returned",feedback);
                    return new JSONObject(feedback.toString());
                }catch(Exception error){original=error;throw error;}
                finally{
                    try{resources.release(reservation);}
                    catch(Exception release){if(original!=null)original.addSuppressed(release);else throw release;}
                }
            });
            if(!invoked.get())throw new IllegalStateException("resource_ready_callback_missing");
            if(result==null)throw new IllegalStateException("resource_result_missing");
            return result;
        }catch(Exception failure){
            try{resources.cancel(admission,"request_failed_or_reauthorization_required");}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try{log.record(binding,requestId,"request_stopped",new JSONObject().put("error_type",failure.getClass().getSimpleName()));}
            catch(Exception logging){failure.addSuppressed(logging);}
            throw failure;
        }
    }
    private void requestApproval(SessionBinding binding,OperationDescription operation,String reason)throws Exception{
        log.record(binding,operation.requestId,"approval_required",new JSONObject().put("reason",reason));
        authorization.requestApproval(operation,reason);
    }
}
