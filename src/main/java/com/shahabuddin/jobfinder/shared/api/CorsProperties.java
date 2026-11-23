package com.shahabuddin.jobfinder.shared.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

/**
 * Which browser origins may call the API (see docs/adr/0007-cors-policy.md).
 *
 * <pre>
 * jobfinder.cors.allowed-origins=https://app.example.com,https://admin.example.com
 * # or as an environment variable: JOBFINDER_CORS_ALLOWEDORIGINS=https://app.example.com
 * </pre>
 *
 * The default is <b>no origins</b>: cross-origin browser requests are rejected until an environment
 * explicitly allows its frontend. Every entry is validated at startup, because Spring only detects a
 * bad value (e.g. {@code "*"} combined with credentials) when the first request fails with a 500.
 *
 * @param allowedOrigins exact origins ({@code scheme://host[:port]}); no wildcards, no paths
 * @param maxAge         how long browsers may cache a preflight response
 */
@ConfigurationProperties("jobfinder.cors")
record CorsProperties(@DefaultValue List<String> allowedOrigins, @DefaultValue("1h") Duration maxAge) {

    CorsProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
        allowedOrigins.forEach(CorsProperties::requireOrigin);
        if (maxAge.isNegative()) {
            throw new IllegalArgumentException("jobfinder.cors.max-age must not be negative, was " + maxAge);
        }
    }

    private static void requireOrigin(String origin) {
        if (!isOrigin(origin)) {
            throw new IllegalArgumentException("Invalid CORS origin '" + origin
                    + "': expected scheme://host[:port], e.g. https://app.example.com (http or https, no path, no wildcard)");
        }
    }

    private static boolean isOrigin(String origin) {
        if (origin.contains("*")) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            String path = uri.getRawPath();
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (path == null || path.isEmpty() || path.equals("/"))   // Spring ignores a trailing "/"
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException invalid) {
            return false;
        }
    }
}
