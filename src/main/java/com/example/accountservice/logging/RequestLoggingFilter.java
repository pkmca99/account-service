package com.example.accountservice.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a request id and logs one line per request (method, path, status, time taken).
 *
 * <ul>
 *   <li>Uses the caller's {@code X-Request-Id} header when it is present and safe, otherwise generates one.</li>
 *   <li>Puts it in the MDC as {@code requestId}, so every log line written while handling the request carries it.</li>
 *   <li>Returns it in the {@code X-Request-Id} response header so a client can quote it when reporting a problem.</li>
 * </ul>
 * Actuator calls (health probes every few seconds) are logged at DEBUG only, to keep the logs readable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** Only accept short, plain ids from callers, so a client cannot inject odd characters into the logs. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            logRequest(request, response.getStatus(), tookMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static void logRequest(HttpServletRequest request, int status, long tookMs) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean actuator = path.startsWith("/actuator");

        if (actuator) {
            log.debug("{} {} -> {} ({} ms)", method, path, status, tookMs);
        } else if (status >= 500) {
            log.error("{} {} -> {} ({} ms)", method, path, status, tookMs);
        } else if (status >= 400) {
            log.warn("{} {} -> {} ({} ms)", method, path, status, tookMs);
        } else {
            log.info("{} {} -> {} ({} ms)", method, path, status, tookMs);
        }
    }

    private static String resolveRequestId(String fromHeader) {
        if (fromHeader != null && SAFE_ID.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString();
    }
}
