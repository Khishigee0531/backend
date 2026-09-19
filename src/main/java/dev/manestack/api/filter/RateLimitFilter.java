package dev.manestack.api.filter;

import dev.manestack.service.RedisService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.time.Duration;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RateLimitFilter {

    @Inject
    RedisService redisService;

    private static final int DEFAULT_MAX_REQUESTS = 100;
    private static final int LOGIN_MAX_REQUESTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @ServerRequestFilter
    public Uni<Response> filter(ContainerRequestContext requestContext) {
        // Get IP from the underlying connection (cannot be spoofed)
        String ip = "unknown";
        try {
            // For RESTEasy Reactive, we can get the remote address from the request
            var request = (io.vertx.core.http.HttpServerRequest) requestContext.getProperty("io.vertx.core.http.HttpServerRequest");
            if (request != null) {
                var remoteAddress = request.remoteAddress();
                if (remoteAddress != null) {
                    ip = remoteAddress.host();
                }
            }
        } catch (Exception e) {
            // Fallback to unknown
        }

        String path = requestContext.getUriInfo().getPath();

        // Tighter rate limit for login endpoint
        int maxRequests = path.contains("/login") ? LOGIN_MAX_REQUESTS : DEFAULT_MAX_REQUESTS;
        String rateLimitKey = "ratelimit:" + ip + ":" + path;

        return redisService.checkRateLimit(rateLimitKey, maxRequests, WINDOW)
                .map(allowed -> {
                    if (!allowed) {
                        return Response.status(429)
                                .entity("{\"error\":\"Too many requests\"}")
                                .build();
                    }
                    return null;
                });
    }
}
