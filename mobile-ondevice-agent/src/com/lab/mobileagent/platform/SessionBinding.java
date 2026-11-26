package com.lab.mobileagent.platform;

import org.json.JSONObject;

/**
 * Immutable binding supplied by a trusted launcher/system adapter, never an MCP argument.
 * A value object is not authentication. The adapter must resolve UID and UI ownership in Android.
 */
public final class SessionBinding {
    public final String sessionId, taskId, taskDescription, callerId, targetPackage, uiInstanceId, bindingKind;
    public final int callerUid, targetUid, displayId;
    private final String encoded, fingerprint;

    private SessionBinding(String session, String task, String description, String caller, int callerUid,
            String target, int targetUid, int display, String instance, String kind) throws Exception {
        this.sessionId = JsonSnapshot.text(session, "session_id", 128);
        this.taskId = JsonSnapshot.text(task, "task_id", 128);
        this.taskDescription = JsonSnapshot.text(description, "task_description", 16384);
        this.callerId = JsonSnapshot.text(caller, "caller_id", 256);
        this.targetPackage = JsonSnapshot.text(target, "target_package", 256);
        if (!target.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || callerUid < 0 || targetUid < 0 || display < 0) throw new IllegalArgumentException("invalid_platform_binding");
        this.uiInstanceId = JsonSnapshot.text(instance, "ui_instance", 256);
        this.bindingKind = kind;
        this.callerUid = callerUid; this.targetUid = targetUid; this.displayId = display;
        encoded = JsonSnapshot.object(new JSONObject().put("session_id", sessionId).put("task_id", taskId)
                .put("task_description", taskDescription).put("caller_id", callerId).put("caller_uid", callerUid)
                .put("target_package", targetPackage).put("target_uid", targetUid).put("display_id", displayId)
                .put("ui_instance_id", uiInstanceId).put("binding_kind", bindingKind));
        fingerprint = JsonSnapshot.digest(encoded);
    }

    /** Only for an operator-owned dedicated emulator. targetUid must be resolved by Control, not copied from a model. */
    public static SessionBinding stockEmulator(String sessionId, String taskId, String taskDescription,
            String targetPackage, int resolvedTargetUid) throws Exception {
        if (!(resolvedTargetUid >= 10000 && resolvedTargetUid <= 19999)
                && !(resolvedTargetUid == 1000 && "com.android.settings".equals(targetPackage)))
            throw new SecurityException("stock_target_uid_out_of_scope");
        return new SessionBinding(sessionId, taskId, taskDescription, "operator_launched_android_shell", 2000,
                targetPackage, resolvedTargetUid, 0, "dedicated_emulator_display_0", "dedicated_emulator_scope");
    }

    /** Reserved for a system service after caller/app approval and framework target resolution. Does not create a grant. */
    public static SessionBinding systemResolved(String sessionId, String taskId, String taskDescription,
            String authenticatedCallerId, int authenticatedCallerUid, String targetPackage, int resolvedTargetUid,
            int displayId, String resolvedUiInstanceId) throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("system_session_virtual_display_required");
        return new SessionBinding(sessionId, taskId, taskDescription, authenticatedCallerId, authenticatedCallerUid,
                targetPackage, resolvedTargetUid, displayId, resolvedUiInstanceId, "system_resolved_virtual_ui");
    }
    public JSONObject json() throws Exception { return new JSONObject(encoded); }
    public String fingerprint() { return fingerprint; }
}
