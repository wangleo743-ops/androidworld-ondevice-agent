package com.lab.mobileagent.model;

public final class Observation {
    public final byte[] png;
    public final int width, height, displayId;
    public final String packageName, component;
    public final long capturedNanos;
    public final long actionBoundaryNanos, observedNanos;
    public final boolean frameAfterAction;
    public Observation(byte[] png, int width, int height, int displayId,
                       String packageName, String component) {
        this(png,width,height,displayId,packageName,component,System.nanoTime());
    }
    public Observation(byte[] png, int width, int height, int displayId,
                       String packageName, String component,long capturedNanos) {
        this(png,width,height,displayId,packageName,component,capturedNanos,0,true);
    }
    public Observation(byte[] png, int width, int height, int displayId,
                       String packageName, String component,long capturedNanos,long actionBoundaryNanos,boolean frameAfterAction) {
        this(png,width,height,displayId,packageName,component,capturedNanos,actionBoundaryNanos,System.nanoTime(),frameAfterAction);
    }
    public Observation(byte[] png, int width, int height, int displayId,
                       String packageName, String component,long capturedNanos,long actionBoundaryNanos,long observedNanos,boolean frameAfterAction) {
        this.png=png; this.width=width; this.height=height; this.displayId=displayId;
        this.packageName=packageName; this.component=component;
        this.capturedNanos=capturedNanos;
        this.actionBoundaryNanos=actionBoundaryNanos;this.frameAfterAction=frameAfterAction;
        this.observedNanos=observedNanos;
    }
}
