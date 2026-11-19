package engine;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The engine is an <b>internal</b> service: only the hub may load definitions and run codecs on it. With
 * {@code INTERNAL_TOKEN} set, every {@code /engine/**} request must carry {@code X-Internal-Token} (constant-time
 * compare), otherwise 401. Without it anyone could load trees and spend its CPU and memory. Exception: the
 * read-only {@code GET /engine/loaded} list the dev console shows through Caddy.
 *
 * <p>Unset token = open (transitional, so the code can be deployed before the variable exists).
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";

    private final byte[] token;

    public InternalTokenFilter(@Value("${internal.token:}") String token) {
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (token == null || !path.startsWith("/engine/")) return true;
        return "GET".equals(request.getMethod()) && path.equals("/engine/loaded");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        if (sent == null || !MessageDigest.isEqual(token, sent.trim().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"internal service: X-Internal-Token required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
