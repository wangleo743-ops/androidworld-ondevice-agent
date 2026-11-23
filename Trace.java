package com.lab.mobileagent.telemetry;

import android.os.Debug;
import android.os.Process;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Inclusive nested timings measured on the phone; never sum parent and child spans. */
public final class Trace {
    public final String id=UUID.randomUUID().toString();
    private final long origin=System.nanoTime(), cpuOrigin=Process.getElapsedCpuTime();
    private final JSONArray spans=new JSONArray();
    private boolean collectJavaHeap;
    public void enableHeapSampling(){collectJavaHeap=true;}
    private static long javaHeapUsed(){Runtime rt=Runtime.getRuntime();return rt.totalMemory()-rt.freeMemory();}
    private final ThreadLocal<ArrayDeque<String>> stack=ThreadLocal.withInitial(ArrayDeque::new);
    public Scope span(String layer,String name) {return new Scope(layer,name);}
    public final class Scope implements AutoCloseable {
        private final String sid=UUID.randomUUID().toString(), parent=stack.get().peek(),layer,name;
        private final long start=System.nanoTime(),cpu=Process.getElapsedCpuTime(),thread=Debug.threadCpuTimeNanos();
        private final long heapAtStart=collectJavaHeap?javaHeapUsed():-1L;
        private String error=null;
        private boolean closed=false;
        private Scope(String layer,String name){this.layer=layer;this.name=name;stack.get().push(sid);}
        public void fail(String safeCode){error=safeCode;}
        public void close(){
            if(closed)return;closed=true;
            long end=System.nanoTime(),pcpu=Process.getElapsedCpuTime(),tcpu=Debug.threadCpuTimeNanos();
            stack.get().pop();
            try {JSONObject row=new JSONObject().put("trace_id",id).put("span_id",sid)
                .put("parent_span_id",parent==null?JSONObject.NULL:parent).put("layer",layer).put("name",name)
                .put("start_seconds",(start-origin)/1e9).put("end_seconds",(end-origin)/1e9)
                .put("wall_seconds",(end-start)/1e9).put("process_cpu_seconds",(pcpu-cpu)/1000.0)
                .put("thread_cpu_seconds",(tcpu-thread)/1e9).put("success",error==null)
                .put("error",error==null?JSONObject.NULL:error);
                if(heapAtStart>=0)row.put("java_heap_used_start_bytes",heapAtStart).put("java_heap_used_end_bytes",javaHeapUsed());
                synchronized(spans){spans.put(row);}
            }catch(Exception ignored){throw new IllegalStateException("trace_record_failed");}
        }
    }
    public JSONObject report() throws Exception {
        synchronized(spans){return new JSONObject().put("schema_version",1).put("trace_id",id)
            .put("pid",Process.myPid()).put("uid",Process.myUid())
            .put("elapsed_seconds",(System.nanoTime()-origin)/1e9)
            .put("agent_process_cpu_seconds",(Process.getElapsedCpuTime()-cpuOrigin)/1000.0)
            .put("scope","phone_agent_process; child system commands/apps/model CPU excluded; spans inclusive")
            .put("spans",new JSONArray(spans.toString()));}
    }
}
