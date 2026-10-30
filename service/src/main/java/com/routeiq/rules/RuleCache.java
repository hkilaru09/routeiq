package com.routeiq.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.routeiq.domain.RoutingRule;
import com.routeiq.store.Json;
import com.routeiq.store.RuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-through Redis cache of each tenant's enabled rules, ordered by priority. Rule writes evict the tenant's key.
 * If Redis is unavailable the router falls back to Postgres rather than stalling.
 */
@Component
public class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);
    private static final TypeReference<List<RoutingRule>> RULES = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final RuleRepository repository;
    private final Json json;
    private final MeterRegistry meters;
    private final Duration ttl;

    public RuleCache(StringRedisTemplate redis, RuleRepository repository, Json json, MeterRegistry meters,
                     @Value("${routeiq.rules.cache-ttl:PT10M}") Duration ttl) {
        this.redis = redis;
        this.repository = repository;
        this.json = json;
        this.meters = meters;
        this.ttl = ttl;
    }

    public List<RoutingRule> rulesFor(String tenantId) {
        String key = key(tenantId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                meters.counter("routeiq.rules.cache", "result", "hit").increment();
                return json.read(cached, RULES);
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for {}, falling back to database: {}", key, e.toString());
            meters.counter("routeiq.rules.cache", "result", "error").increment();
            return load(tenantId);
        }
        meters.counter("routeiq.rules.cache", "result", "miss").increment();
        List<RoutingRule> rules = load(tenantId);
        try {
            redis.opsForValue().set(key, json.write(rules), ttl);
        } catch (RuntimeException e) {
            log.warn("Redis write failed for {}: {}", key, e.toString());
        }
        return rules;
    }

    public void evict(String tenantId) {
        redis.delete(key(tenantId));
    }

    private List<RoutingRule> load(String tenantId) {
        return repository.forTenant(tenantId).stream().filter(RoutingRule::enabled).toList();
    }

    static String key(String tenantId) {
        return "routeiq:rules:" + tenantId;
    }
}
