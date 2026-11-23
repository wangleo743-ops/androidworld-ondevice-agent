package com.lab.mobileagent.service;

import org.json.JSONObject;

/**
 * Trusted, session-scoped scheduling port. A system-shell adapter can supply this port
 * to GuiMcpService after resolving the task/display. It is never published as an MCP tool.
 * Admission and final dispatch checks belong to the execution service.
 */
public interface SessionResourceManager extends AutoCloseable {
    interface Check { void run() throws Exception; }
    interface Work { JSONObject run() throws Exception; }
    interface OwnedReclaimer { boolean releaseIfExclusivelyOwned() throws Exception; }

    static SessionResourceManager local(String taskId, int displayId, String mode) {
        if (taskId == null || taskId.isEmpty() || displayId <= 0)
            throw new IllegalArgumentException("missing_resource_session_binding");
        return new ResourceOperationQueue(taskId, displayId, mode);
    }
    String uiMode();
    JSONObject submit(Object id, Check admission, Check enqueued, Check recheck, Work work) throws Exception;
    void cancel(Object id);
    boolean reclaim(OwnedReclaimer owner) throws Exception;
    @Override void close();
    boolean isClosed();
    JSONObject report() throws Exception;
}
