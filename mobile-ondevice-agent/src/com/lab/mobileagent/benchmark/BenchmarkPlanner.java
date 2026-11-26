package com.lab.mobileagent.benchmark;

import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.model.Observation;
import org.json.JSONArray;
import org.json.JSONObject;

/** An independent model process proposes an action; it never executes benchmark tools. */
public interface BenchmarkPlanner extends AutoCloseable {
    Decision decide(String task, Observation observation, JSONArray history) throws Exception;
    JSONObject identity() throws Exception;
    @Override void close() throws Exception;
}
