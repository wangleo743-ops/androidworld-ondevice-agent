package com.lab.mobileagent.standard;

import com.lab.mobileagent.l1.StrictJson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Newline-delimited MCP JSON-RPC. The limit includes base64 images and metadata. */
public final class StockWire {
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    public static final int MAX_REQUEST_BYTES = 32768;
    public static final String VERSION = "2025-06-18";
    private StockWire() {}

    public static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                if (bytes.size() == 0) throw new IllegalArgumentException("stock_empty_frame");
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            }
            if (bytes.size() >= limit) throw new IllegalArgumentException("stock_frame_too_large");
            bytes.write(value);
        }
        if (bytes.size() != 0) throw new IllegalArgumentException("stock_partial_frame");
        return null;
    }

    public static JSONObject object(String line) {
        if (line == null) throw new IllegalStateException("stock_peer_eof");
        return StrictJson.parseObject(line);
    }

    public static void write(OutputStream out, JSONObject object, int limit) throws Exception {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IllegalArgumentException("stock_frame_too_large");
        out.write(bytes); out.write('\n'); out.flush();
    }
}
