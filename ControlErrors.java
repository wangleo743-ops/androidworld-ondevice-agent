package com.lab.mobileagent.runtime;

/** Error codes safe to include in evidence: never serialize arbitrary exception messages. */
public final class ControlErrors {
    private ControlErrors() {}
    public static final class Denied extends Exception {
        public Denied(String code) { super(code); }
    }
    public static String safe(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[a-z][a-z0-9_]{0,100}")
                ? message : "validation_or_execution_failed";
    }
}
