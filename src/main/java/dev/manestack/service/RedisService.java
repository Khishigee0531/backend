package dev.manestack.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import static io.vertx.mutiny.redis.client.Request.cmd;
import static io.vertx.mutiny.redis.client.Command.*;

@ApplicationScoped
public class RedisService {

    private static final String RATE_LIMIT_PREFIX = "poker:ratelimit:";

    @Inject
    Redis redis;

    public Uni<Boolean> checkRateLimit(String key, int maxRequests, Duration window) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        return redis.send(cmd(EXISTS).arg(redisKey))
                .map(Response::toInteger)
                .chain(exists -> {
                    if (exists > 0) {
                        return redis.send(cmd(INCR).arg(redisKey))
                                .map(r -> r.toLong() <= maxRequests);
                    } else {
                        return redis.send(cmd(SETEX)
                                .arg(redisKey)
                                .arg(window.toSeconds())
                                .arg("1")
                        ).map(r -> true);
                    }
                });
    }
}
