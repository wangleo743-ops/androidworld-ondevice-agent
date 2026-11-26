package com.lab.mobileagent.platform;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

/** Baseline-only durable JSONL log. CREATE_NEW prevents rewriting a previous experiment. */
public final class FileExecutionLog implements ExecutionLogSink {
    private final FileChannel channel;
    private long sequence;
    private boolean closed;
    public FileExecutionLog(File freshPath)throws Exception{
        if(freshPath==null||freshPath.getParentFile()==null||!freshPath.getParentFile().isDirectory())
            throw new IllegalArgumentException("log_parent_required");
        channel=FileChannel.open(freshPath.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
    @Override public synchronized void record(SessionBinding binding,String stepId,String phase,JSONObject details)throws Exception{
        if(closed)throw new IllegalStateException("execution_log_closed");
        if(binding==null)throw new IllegalArgumentException("missing_log_session");
        JSONObject event=new JSONObject().put("schema","execution_event_v1").put("sequence",++sequence)
                .put("session_id",binding.sessionId).put("task_id",binding.taskId).put("binding_hash",binding.fingerprint())
                .put("step_id",JsonSnapshot.text(stepId,"step_id",128)).put("phase",JsonSnapshot.text(phase,"phase",128))
                .put("epoch_ms",System.currentTimeMillis()).put("monotonic_ns",System.nanoTime())
                .put("details",new JSONObject(JsonSnapshot.object(details)));
        ByteBuffer bytes=ByteBuffer.wrap((event.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        while(bytes.hasRemaining())channel.write(bytes);
        channel.force(false);
    }
    @Override public synchronized void close()throws Exception{
        if(closed)return;closed=true;
        try{channel.force(false);}finally{channel.close();}
    }
}
