package dev.manestack.api.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import io.vertx.mutiny.redis.client.Redis;

import static io.vertx.mutiny.redis.client.Request.cmd;
import static io.vertx.mutiny.redis.client.Command.*;

@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(RedisHealthCheck.class);

    @Inject
    Redis redis;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Redis connection");
        try {
            redis.send(cmd(PING)).await().indefinitely();
            builder.up().withData("status", "connected");
        } catch (Exception e) {
            LOG.error("Redis health check failed", e);
            builder.down().withData("error", e.getMessage());
        }
        return builder.build();
    }
}
