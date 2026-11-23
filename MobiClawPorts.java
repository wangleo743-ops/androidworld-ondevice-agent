package com.lab.mobileagent.platform;

import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import org.json.JSONObject;

/**
 * Extension points for paper 4.1/4.2, not Android or deployed MobiClaw implementations.
 * Implementations must live in the trusted service/framework boundary; never export grant mutation,
 * process controls, arbitrary resource commands or these provider objects to model-facing MCP tools.
 */
public final class MobiClawPorts {
    private MobiClawPorts(){}
    public interface Clock { long epochMillis(); }

    public interface Sessions {
        /** Checks automation permission and target-app approval BEFORE activating an execution binding. */
        SessionBinding openApproved(String taskDescription,String operatorSelectedPackage)throws Exception;
        void requireLive(SessionBinding binding)throws Exception;
        void close(SessionBinding binding)throws Exception;
    }
    public static final class DisplayListFrame {
        public final String bindingHash,observationId,revision;
        public final long sampledNanos;
        private final String textAndGeometry;
        public DisplayListFrame(SessionBinding binding,String observationId,String revision,long sampledNanos,JSONObject data)throws Exception{
            if(binding==null||sampledNanos<=0)throw new IllegalArgumentException("invalid_display_list_frame");
            bindingHash=binding.fingerprint();this.observationId=JsonSnapshot.text(observationId,"observation_id",256);
            this.revision=JsonSnapshot.text(revision,"revision",256);this.sampledNanos=sampledNanos;textAndGeometry=JsonSnapshot.object(data);
        }
        public JSONObject textAndGeometry()throws Exception{return new JSONObject(textAndGeometry);}
    }
    public interface Rendering {
        Observation pixels(SessionBinding binding)throws Exception;
        DisplayListFrame displayList(SessionBinding binding)throws Exception;
        /** Must return evidence of completion, not merely an accepted refresh request. */
        String requestUpdate(SessionBinding binding)throws Exception;
    }
    public interface OperationResolver {
        OperationDescription resolve(SessionBinding binding,String requestId,Action action,DisplayListFrame frame)throws Exception;
    }
    public interface Authorization {
        /** Returns null if no valid matching entry exists. Must not approve a request on the agent's behalf. */
        AuthorizationRecord match(OperationDescription operation)throws Exception;
        void requestApproval(OperationDescription operation,String reason)throws Exception;
        /** Store-owned atomic recheck/one-operation consumption; no reconstruction of OperationDescription. */
        Recheck recheck(AuthorizationRecord record)throws Exception;
    }
    public enum Recheck { VALID, ENTRY_CHANGED, REVOKED, EXPIRED, SCOPE_ENDED, ALREADY_USED, UNAVAILABLE }
    public static final class Admission {
        public final String id,requestId;
        public Admission(String id,String requestId){this.id=JsonSnapshot.text(id,"admission_id",256);this.requestId=JsonSnapshot.text(requestId,"request_id",128);}
    }
    public static final class Reservation {
        public final String id,admissionId;
        public Reservation(String id,String admissionId){this.id=JsonSnapshot.text(id,"reservation_id",256);this.admissionId=JsonSnapshot.text(admissionId,"admission_id",256);}
    }
    public interface ReadyCallback { JSONObject execute(Reservation reservation)throws Exception; }
    public interface Resources {
        Admission enqueue(SessionBinding binding,OperationDescription command,AuthorizationRecord authorization)throws Exception;
        /** The RM calls ready exactly once after selection, with a reservation owned by this admission. */
        JSONObject whenReady(Admission admission,ReadyCallback ready)throws Exception;
        void cancel(Admission admission,String reason)throws Exception;
        /** Release the unused/restored allocation after denial, cancellation or completion. Must be idempotent. */
        void release(Reservation reservation)throws Exception;
    }
    public interface Commands {
        /** Resolve binding/revision validity without rebuilding the authorization description. Stale target => new request. */
        void requireCurrentTarget(SessionBinding binding,OperationDescription command)throws Exception;
        JSONObject deliver(SessionBinding binding,OperationDescription command)throws Exception;
    }
    public static final class UiStateRef {
        public final String stateId,bindingHash,stepId,frameworkIssuer,sha256;
        public UiStateRef(SessionBinding binding,String stepId,String stateId,String frameworkIssuer,String sha256){
            if(binding==null||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_ui_state_reference");
            this.bindingHash=binding.fingerprint();this.stepId=JsonSnapshot.text(stepId,"step_id",128);
            this.stateId=JsonSnapshot.text(stateId,"state_id",256);this.frameworkIssuer=JsonSnapshot.text(frameworkIssuer,"framework_issuer",256);this.sha256=sha256;
        }
    }
    public interface UiStateExport {
        /** App/framework -> logging directly, after observation completes and the agent traversal is paused. */
        UiStateRef exportToLogging(SessionBinding binding,String completedStepId,String completedObservationId)throws Exception;
    }
    public interface Recovery {
        /** Best effort; never treat UI equality as proof of completed business effects. */
        JSONObject reconstruct(SessionBinding binding,UiStateRef state)throws Exception;
        JSONObject checkReconstructedState(SessionBinding binding,UiStateRef expected)throws Exception;
        /** Replay implementations MUST call submit on the execution service for every safely repeatable action. */
        JSONObject replay(SessionBinding binding,UiStateRef target,ReplaySubmission executionService)throws Exception;
    }
    public interface ReplaySubmission { JSONObject submit(String replayStepId,Action repeatableAction)throws Exception; }
    public interface SecondUi {
        /** Framework business coordination is distinct from merely allocating a virtual display. */
        JSONObject coordinate(SessionBinding binding,String supportedBusinessOperation,JSONObject parameters)throws Exception;
    }
    public static void requireSystemImplementation(){
        throw new UnsupportedOperationException("mobiclaw_system_backend_not_installed");
    }
}
