package com.lab.mobileagent.standard;

import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Portable process-pipe transport; no Android or network API and no API credentials. */
public final class StockMcpConnection implements AutoCloseable {
    private final Process child;
    private final long deadline, callTimeoutMillis;
    private final ArrayBlockingQueue<String> replies = new ArrayBlockingQueue<>(2);
    private final AtomicReference<String> terminal = new AtomicReference<>();
    private final ExecutorService writer;
    private final Thread reader, errors;
    private long sequence;
    private boolean closing, closed, exitConfirmed, executionRejected;
    private int requests;
    private JSONObject finalReport, lastToolError;

    /** A well-formed tool failure leaves the channel available only for cleanup. */
    public static final class ToolRejectedException extends IllegalStateException {
        ToolRejectedException(String safeCode) { super(safeCode); }
    }

    public StockMcpConnection(Process child, JSONObject bootstrap, long deadlineNanos, long timeoutMillis) throws Exception {
        if (child == null || timeoutMillis < 1 || deadlineNanos <= System.nanoTime())
            throw new IllegalArgumentException("stock_connection_configuration_invalid");
        this.child = child; deadline = deadlineNanos; callTimeoutMillis = timeoutMillis;
        writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "stock-mcp-write"); t.setDaemon(true); return t; });
        reader = new Thread(() -> {
            try (InputStream input = child.getInputStream()) {
                String frame;
                while ((frame = StockWire.read(input, StockWire.MAX_FRAME_BYTES)) != null) {
                    if (!replies.offer(frame)) throw new IllegalStateException("stock_unsolicited_output");
                }
                terminal.compareAndSet(null, "stock_peer_eof");
            } catch (Exception failure) { terminal.compareAndSet(null, "stock_invalid_or_closed_pipe"); child.destroyForcibly(); }
        }, "stock-mcp-read");
        errors = new Thread(() -> {
            // Diagnostics may accidentally contain secrets in future implementations: count, never persist/echo.
            try (InputStream input = child.getErrorStream()) {
                byte[] buffer = new byte[2048]; int n, total = 0;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > 65536) { terminal.compareAndSet(null, "stock_stderr_limit"); child.destroyForcibly(); break; }
                }
            } catch (Exception ignored) {}
        }, "stock-mcp-stderr");
        reader.setDaemon(true); errors.setDaemon(true); reader.start(); errors.start();
        try {
            send(bootstrap);
            JSONObject result = request("initialize", new JSONObject().put("protocolVersion", StockWire.VERSION)
                    .put("capabilities", new JSONObject()).put("clientInfo", new JSONObject().put("name", "stock-phone-agent").put("version", "1")));
            if (!StockWire.VERSION.equals(result.optString("protocolVersion")) || result.optJSONObject("capabilities") == null
                    || result.optJSONObject("serverInfo") == null) throw new IllegalStateException("stock_mcp_negotiation_invalid");
            send(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        } catch (Exception failure) { abort(); throw failure; }
    }

    private long remainingMillis() {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IllegalStateException("stock_mcp_deadline");
        return Math.max(1, Math.min(callTimeoutMillis, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    private void send(JSONObject message) throws Exception {
        final JSONObject snapshot = StockWire.object(message.toString());
        Future<?> sent = writer.submit(() -> {
            try { StockWire.write(child.getOutputStream(), snapshot, StockWire.MAX_REQUEST_BYTES); }
            catch (Exception failure) { throw new IllegalStateException("stock_write_failed", failure); }
        });
        try { sent.get(remainingMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception failure) { sent.cancel(true); throw new IllegalStateException("stock_write_failed_or_timeout"); }
    }

    public synchronized JSONObject request(String method, JSONObject params) throws Exception {
        if (closed || closing) throw new IllegalStateException("stock_connection_closed");
        // This guard also covers callers of request(), not just callTool(). Never
        // put it inside the wire-failure catch: refusal must not kill cleanup.
        if (executionRejected && !closeRequest(method, params))
            throw new IllegalStateException("stock_connection_cleanup_only");
        if (terminal.get() != null) throw fail(terminal.get());
        String id = "stock-" + (++sequence);
        try {
            send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params));
            requests++;
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis());
            String line;
            for (;;) {
                line = replies.poll(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))), TimeUnit.MILLISECONDS);
                if (line != null) break;
                if (terminal.get() != null) throw new IllegalStateException(terminal.get());
                if (System.nanoTime() >= until) throw new IllegalStateException("stock_response_timeout");
            }
            JSONObject reply = StockWire.object(line);
            if (!"2.0".equals(reply.optString("jsonrpc")) || !id.equals(reply.opt("id"))
                    || reply.has("result") == reply.has("error")) throw new IllegalStateException("stock_response_binding_invalid");
            if (reply.has("error")) throw new IllegalStateException("stock_mcp_request_rejected");
            JSONObject result = reply.optJSONObject("result");
            if (result == null) throw new IllegalStateException("stock_mcp_result_invalid");
            return result;
        } catch (Exception failure) { abort(); throw failure; }
    }

    public synchronized JSONObject callTool(String name, JSONObject arguments) throws Exception {
        JSONObject result = request("tools/call", new JSONObject().put("name", name).put("arguments", arguments));
        if (!(result.opt("isError") instanceof Boolean) || result.optJSONObject("structuredContent") == null
                || result.optJSONArray("content") == null) throw fail("stock_tool_result_invalid");
        if (result.getBoolean("isError")) {
            // Do not store arbitrary server text/arguments. The service returns a
            // ControlErrors.safe reason; unknown text is reduced to a fixed code.
            Object value = result.getJSONObject("structuredContent").opt("reason");
            String reason = value instanceof String ? (String)value : "";
            String safeCode = reason.matches("[a-z][a-z0-9_]{0,100}") ? reason : "stock_tool_execution_rejected";
            executionRejected = true;
            if (lastToolError == null) lastToolError = new JSONObject().put("code", safeCode)
                    .put("tool", safeTool(name)).put("request_id", "stock-" + sequence)
                    .put("kind", "valid_mcp_tool_failure")
                    .put("execution_state", "may_have_been_dispatched_do_not_retry");
            throw new ToolRejectedException(safeCode);
        }
        return result;
    }

    private static boolean closeRequest(String method, JSONObject params) {
        return "tools/call".equals(method) && params != null && params.length() == 2
                && "control.close".equals(params.opt("name"))
                && params.optJSONObject("arguments") != null && params.optJSONObject("arguments").length() == 0;
    }

    private static String safeTool(String name) {
        return name != null && name.matches("(?:gui|control)\\.[a-z_]{1,32}") ? name : "unknown_tool";
    }

    private IllegalStateException fail(String reason) { abort(); return new IllegalStateException(reason); }

    private void abort() {
        terminal.compareAndSet(null, "stock_connection_aborted"); closing = true;
        try { child.getOutputStream().close(); } catch (Exception ignored) {}
        child.destroyForcibly();
        try { exitConfirmed = child.waitFor(2000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        writer.shutdownNow();
        try { child.getInputStream().close(); } catch (Exception ignored) {}
        try { child.getErrorStream().close(); } catch (Exception ignored) {}
        closed = exitConfirmed;
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        Exception failure = null;
        if (!closing && terminal.get() == null && System.nanoTime() < deadline) {
            try { finalReport = callTool("control.close", new JSONObject()).getJSONObject("structuredContent"); }
            catch (Exception error) { failure = error; }
        }
        closing = true;
        try { child.getOutputStream().close(); } catch (Exception ignored) {}
        try {
            if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroyForcibly();
            exitConfirmed = child.waitFor(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failure = interrupted; child.destroyForcibly(); }
        writer.shutdownNow();
        try { child.getInputStream().close(); } catch (Exception ignored) {}
        try { child.getErrorStream().close(); } catch (Exception ignored) {}
        closed = exitConfirmed;
        if (!exitConfirmed) throw new IllegalStateException("stock_child_exit_unconfirmed");
        if (failure != null) throw failure;
    }

    public synchronized JSONObject report() throws Exception {
        return new JSONObject().put("transport", "stdio_jsonrpc").put("protocol", "MCP")
                .put("protocol_version", StockWire.VERSION).put("requests", requests)
                .put("closed", closed).put("child_exit_confirmed", exitConfirmed)
                .put("execution_rejected", executionRejected)
                .put("last_tool_error", lastToolError == null ? JSONObject.NULL : lastToolError)
                .put("wire_frame_limit_bytes", StockWire.MAX_FRAME_BYTES)
                .put("final_server_report", finalReport == null ? JSONObject.NULL : finalReport);
    }
}
