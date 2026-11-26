package com.lab.mobileagent.platform;

import org.json.JSONObject;

/** Service -> logging boundary. The production adapter should run outside the agent host. */
public interface ExecutionLogSink extends AutoCloseable {
    void record(SessionBinding binding,String stepId,String phase,JSONObject details)throws Exception;
    @Override void close()throws Exception;
}
