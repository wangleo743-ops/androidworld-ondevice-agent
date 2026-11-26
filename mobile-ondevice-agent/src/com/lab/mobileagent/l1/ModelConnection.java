package com.lab.mobileagent.l1;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

/** Operator-selected network settings, never accepted from a model action or screenshot. */
public final class ModelConnection {
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses";
    private ModelConnection() {}

    public static String endpoint(String value, boolean allowHttp) {
        if (value == null || value.isEmpty() || value.length() > 2048)
            throw new IllegalArgumentException("invalid_responses_endpoint");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) <= 32 || value.charAt(i) > 126)
                throw new IllegalArgumentException("invalid_responses_endpoint");
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawPath() == null || !uri.getRawPath().endsWith("/responses"))
                throw new IllegalArgumentException("invalid_responses_endpoint");
            return value;
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("invalid_responses_endpoint");
        }
    }

    public static boolean validEffort(String value) {
        return Arrays.asList("default", "none", "minimal", "low", "medium", "high", "xhigh").contains(value);
    }
}
