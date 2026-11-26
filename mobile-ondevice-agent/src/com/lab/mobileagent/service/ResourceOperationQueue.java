package com.lab.mobileagent.service;

import com.lab.mobileagent.runtime.ControlErrors.Denied;
import java.util.ArrayDeque;
import org.json.JSONObject;

/** One FIFO per bound UI. Each caller executes its own accepted job on the calling thread. */
final class ResourceOperationQueue implements SessionResourceManager {
    private static final int CAPACITY = 16;
    private final String taskId, mode;
    private final int displayId;
    ResourceOperationQueue(String taskId, int displayId, String mode) {
        if (!("agent-exclusive".equals(mode) || "concurrent".equals(mode)))
            throw new IllegalArgumentException("invalid_resource_session_mode");
        this.taskId=taskId; this.displayId=displayId; this.mode=mode;
    }
    @Override public String uiMode(){return mode;}
    private static final class Job {
        final Object id;
        long admittedAt;
        final Thread owner = Thread.currentThread();
        boolean cancelled, executing;
        Job(Object id) { this.id=id; }
    }
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private final java.util.Set<Job> checking = new java.util.HashSet<>();
    private Job active;
    private boolean closed, reclaiming;
    private int admitted, dispatchStarted, completed, cancelled, recheckDenied, admissionDenied, highWater, reclaimed, cancelledInAdmission;
    private long totalWaitNanos;

    private void available() throws Denied {
        if (closed) throw new Denied("session_closed");
        if (reclaiming) throw new Denied("session_reclaiming");
    }
    @Override public JSONObject submit(Object id,Check admission,Check enqueued,Check recheck,Work work) throws Exception {
        final Job job=new Job(id);
        synchronized(this){
            available();
            if(checking.size()>=CAPACITY)throw new Denied("approval_request_limit");
            checking.add(job);
        }
        // User approval may take minutes. A session close, cancellation, or an independent
        // authorized caller must not wait on the physical approval UI while holding this lock.
        try { admission.run(); } catch(Exception e) { synchronized(this){checking.remove(job);admissionDenied++;} throw e; }
        synchronized(this) {
            checking.remove(job);
            if(job.cancelled)throw new Denied("request_cancelled_during_approval");
            available();
            if(waiting.size()>=CAPACITY)throw new Denied("resource_queue_full");
            job.admittedAt=System.nanoTime();waiting.addLast(job);
            // Keep the slot private until its session journal confirms admission.
            try {enqueued.run();}
            catch(Exception journalError){waiting.remove(job);notifyAll();throw journalError;}
            admitted++;
            highWater=Math.max(highWater,waiting.size());
            try {
                while(!job.cancelled && (reclaiming || active!=null || waiting.peekFirst()!=job)) wait();
            } catch(InterruptedException e) {
                if(waiting.remove(job)){cancelled++;notifyAll();}
                Thread.currentThread().interrupt();
                throw new Denied("queued_request_interrupted");
            }
            if(job.cancelled)throw new Denied("queued_request_cancelled");
            waiting.removeFirst();active=job;
            totalWaitNanos+=System.nanoTime()-job.admittedAt;
        }
        try {
            try { recheck.run(); }
            catch(Exception e) { synchronized(this){recheckDenied++;} throw e; }
            synchronized(this) {
                if(job.cancelled)throw new Denied("queued_request_cancelled");
                if(closed)throw new Denied("session_closed");
                job.executing=true;dispatchStarted++;
            }
            JSONObject result=work.run();
            if(result==null)throw new IllegalStateException("missing_framework_feedback");
            synchronized(this){completed++;}
            return result;
        } finally {
            synchronized(this){active=null;notifyAll();}
        }
    }
    @Override public synchronized void cancel(Object id) {
        for(Job j:checking)if(!j.cancelled && (id==null || id.equals(j.id))){
            j.cancelled=true;cancelledInAdmission++;
        }
        for(java.util.Iterator<Job> i=waiting.iterator();i.hasNext();) {
            Job j=i.next();
            if(id==null || id.equals(j.id)){j.cancelled=true;cancelled++;i.remove();}
        }
        if(active!=null && !active.executing && !active.cancelled && (id==null || id.equals(active.id))){
            active.cancelled=true;cancelled++;
        }
        notifyAll();
    }
    private void awaitIdle() {
        boolean interrupted=false;
        while(active!=null || reclaiming) {
            try {wait();} catch(InterruptedException e){interrupted=true;}
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    @Override public synchronized void close() {
        if(active!=null && active.owner==Thread.currentThread())throw new IllegalStateException("close_during_dispatch");
        closed=true;cancel(null);awaitIdle();
    }
    @Override public synchronized boolean reclaim(OwnedReclaimer resources) throws Exception {
        if (!"agent-exclusive".equals(mode))return false;
        available();
        if(active!=null && active.owner==Thread.currentThread())throw new Denied("reclaim_during_dispatch");
        reclaiming=true;
        try {
            while(active!=null)wait();
            if(closed)return false;
            // This callback must atomically re-establish exclusive process ownership before
            // releasing that process. An unowned or shared target is never a reclaim candidate.
            boolean released;
            try {released=resources.releaseIfExclusivelyOwned();}
            catch(Exception uncertain){closed=true;cancel(null);throw uncertain;}
            if(!released)return false;
            reclaimed++;closed=true;cancel(null);return true;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new Denied("reclaim_interrupted");
        } finally {reclaiming=false;notifyAll();}
    }
    @Override public synchronized boolean isClosed(){return closed;}
    @Override public synchronized JSONObject report() throws Exception {
        return new JSONObject().put("policy","session_fifo").put("capacity",CAPACITY)
                .put("resource_manager","phone_session_queue").put("task_id",taskId==null?JSONObject.NULL:taskId)
                .put("display_id",displayId<0?JSONObject.NULL:displayId)
                .put("checking_grant",checking.size()).put("waiting",waiting.size()).put("in_flight",active!=null).put("closed",closed)
                .put("admission_denied",admissionDenied).put("admitted",admitted)
                .put("cancelled_during_approval",cancelledInAdmission)
                .put("dispatch_recheck_denied",recheckDenied).put("dispatch_started",dispatchStarted)
                .put("completed",completed).put("cancelled_before_dispatch",cancelled)
                .put("peak_waiting",highWater).put("wait_ns_total",totalWaitNanos).put("exclusive_reclaims",reclaimed);
    }
}
