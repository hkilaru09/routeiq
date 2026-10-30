package com.routeiq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routeiq.domain.IssueStatus;
import com.routeiq.sla.SlaScheduler;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end through real Postgres, Kafka and Redis: HTTP ingest, outbox relay, router worker, notification worker,
 * SLA escalation, retry/dead-letter topics and the WebSocket board.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "routeiq.sla.scan-interval-ms=3600000",
        "routeiq.sla.initial-delay-ms=3600000",
        "routeiq.kafka.retry-attempts=3",
        "routeiq.kafka.retry-delay-ms=100",
        "routeiq.kafka.partitions=2"
})
class PipelineIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final WebhookSink WEBHOOKS;

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
        try {
            WEBHOOKS = new WebhookSink();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @AfterAll
    static void stopSink() {
        WEBHOOKS.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    MutableClock clock;
    @Autowired
    SlaScheduler sla;
    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @BeforeEach
    void pointWebhooksAtSink() {
        clock.reset();
        jdbc.sql("UPDATE teams SET webhook_url = regexp_replace(webhook_url, '^http://[^/]+', ?)")
                .param(WEBHOOKS.baseUrl()).update();
        // Every consumer must own its partitions before we produce, or the board (offset=latest) can miss events.
        await().atMost(Duration.ofSeconds(60)).until(() -> listeners.getAllListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
    }

    @Test
    void issueIsIngestedRoutedAndNotified() throws Exception {
        String key = "cfpb-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "externalId", key,
                "title", "Incorrect information on your report",
                "category", "Credit reporting or other personal consumer reports",
                "attributes", Map.of("state", "WA", "company", "Experian"));

        ResponseEntity<JsonNode> created = post("/api/tenants/cfpb/issues", key, body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asText();

        // Same key, same body: original issue, no duplicate.
        ResponseEntity<JsonNode> replay = post("/api/tenants/cfpb/issues", key, body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asText()).isEqualTo(id);

        // Same key, different body: rejected.
        ResponseEntity<JsonNode> conflict = post("/api/tenants/cfpb/issues", key, Map.of("title", "something else"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        JsonNode issue = awaitStatus("cfpb", id, IssueStatus.ROUTED);
        assertThat(issue.get("teamSlug").asText()).isEqualTo("credit-reporting");
        assertThat(issue.get("slaDueAt")).isNotNull();

        await().atMost(Duration.ofSeconds(30)).until(() -> !WEBHOOKS.callsMentioning(id).isEmpty());
        assertThat(WEBHOOKS.callsMentioning(id).get(0).path()).isEqualTo("/hooks/cfpb/credit-reporting");

        JsonNode detail = get("/api/tenants/cfpb/issues/" + id);
        assertThat(detail.get("events").findValuesAsText("eventType")).containsExactly("CREATED", "ROUTED");
        await().atMost(Duration.ofSeconds(10)).until(() ->
                "SENT".equals(get("/api/tenants/cfpb/issues/" + id).get("notifications").get(0).get("status").asText()));
        assertThat(jdbc.sql("SELECT count(*) FROM issues WHERE external_id = ?").param(key).query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void concurrentRequestsWithOneKeyCreateOneIssue() throws Exception {
        String key = "race-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("externalId", key, "title", "Pothole", "category", "Street Condition");
        var pool = Executors.newFixedThreadPool(8);
        List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit((Callable<ResponseEntity<JsonNode>>) () -> post("/api/tenants/nyc311/issues", key, body)));
        }
        List<String> ids = new ArrayList<>();
        int createdCount = 0;
        for (var f : results) {
            var response = f.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            if (response.getStatusCode() == HttpStatus.CREATED) {
                createdCount++;
            }
            ids.add(response.getBody().get("id").asText());
        }
        pool.shutdown();
        assertThat(createdCount).isEqualTo(1);
        assertThat(ids).containsOnly(ids.get(0));
    }

    @Test
    void ruleChangesTakeEffectImmediately() throws Exception {
        String teamId = jdbc.sql("SELECT id::text FROM teams WHERE tenant_id = 'nyc311' AND slug = 'housing'")
                .query(String.class).single();
        ResponseEntity<JsonNode> rule = http.postForEntity(url("/api/tenants/nyc311/rules"), Map.of(
                "name", "Rodents to housing", "priority", 1, "field", "category", "operator", "EQUALS",
                "matchValue", "Rodent", "teamId", teamId, "slaMinutes", 30), JsonNode.class);
        assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            String id = createIssue("nyc311", Map.of("title", "Rats", "category", "Rodent", "attributes", Map.of("agency", "DOHMH")));
            JsonNode routed = awaitStatus("nyc311", id, IssueStatus.ROUTED);
            assertThat(routed.get("teamSlug").asText()).isEqualTo("housing");
            assertThat(routed.get("ruleId").asText()).isEqualTo(rule.getBody().get("id").asText());
        } finally {
            http.delete(url("/api/tenants/nyc311/rules/" + rule.getBody().get("id").asText()));
        }
        // With the rule gone, the same category falls through to the default team.
        String id = createIssue("nyc311", Map.of("title", "Rats again", "category", "Rodent"));
        assertThat(awaitStatus("nyc311", id, IssueStatus.ROUTED).get("teamSlug").asText()).isEqualTo("general");
    }

    @Test
    void breachedSlaIsEscalatedOnceAndAcknowledgedIssuesAreNot() throws Exception {
        String breaching = createIssue("nyc311", Map.of("title", "Loud party", "category", "Noise - Residential"));
        String acked = createIssue("nyc311", Map.of("title", "Loud music", "category", "Noise - Street/Sidewalk"));
        awaitStatus("nyc311", breaching, IssueStatus.ROUTED);
        awaitStatus("nyc311", acked, IssueStatus.ROUTED);
        assertThat(http.postForEntity(url("/api/tenants/nyc311/issues/" + acked + "/acknowledge"), null, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofHours(3)); // noise SLA is 120 minutes
        sla.scan();
        sla.scan(); // a second scan must not escalate again

        JsonNode detail = get("/api/tenants/nyc311/issues/" + breaching);
        assertThat(detail.get("issue").get("breachedAt")).isNotNull();
        assertThat(detail.get("issue").get("escalationLevel").asInt()).isEqualTo(1);
        assertThat(detail.get("events").findValuesAsText("eventType")).containsExactly("CREATED", "ROUTED", "SLA_BREACHED");
        await().atMost(Duration.ofSeconds(30)).until(() -> WEBHOOKS.callsMentioning(breaching).stream()
                .anyMatch(c -> c.body().contains("\"kind\":\"ESCALATION\"")));

        assertThat(get("/api/tenants/nyc311/issues/" + acked).get("issue").get("breachedAt")).isNull();
    }

    @Test
    void failingWebhookIsRetriedThenDeadLettered() throws Exception {
        jdbc.sql("UPDATE teams SET webhook_url = ? WHERE tenant_id = 'cfpb' AND slug = 'debt-collection'")
                .param(WEBHOOKS.baseUrl() + "/fail/debt-collection").update();
        String id = createIssue("cfpb", Map.of("title", "Collector called at work", "category", "Debt collection"));

        await().atMost(Duration.ofSeconds(60)).until(() ->
                get("/api/tenants/cfpb/issues/" + id).get("events").findValuesAsText("eventType").contains("NOTIFICATION_FAILED"));
        JsonNode notification = get("/api/tenants/cfpb/issues/" + id).get("notifications").get(0);
        assertThat(notification.get("status").asText()).isEqualTo("FAILED");
        assertThat(notification.get("attempts").asInt()).isEqualTo(3); // first try + 2 retry topics
        assertThat(WEBHOOKS.callsMentioning(id)).hasSize(3);
    }

    @Test
    void auditLogIsAppendOnly() {
        String id = createIssue("cfpb", Map.of("title", "Mortgage escrow", "category", "Mortgage"));
        assertThatThrownBy(() -> jdbc.sql("UPDATE issue_events SET event_type = 'X' WHERE issue_id = ?::uuid").param(id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM issue_events WHERE issue_id = ?::uuid").param(id).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void boardReceivesLiveUpdates() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                received.add(message.getPayload());
            }
        }, null, URI.create("ws://localhost:" + port + "/ws/board?tenant=nyc311")).get(10, TimeUnit.SECONDS);
        try {
            String id = createIssue("nyc311", Map.of("title", "Blocked hydrant", "category", "Blocked Driveway",
                    "attributes", Map.of("agency", "NYPD")));
            List<String> types = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!types.contains("ROUTED") && System.nanoTime() < deadline) {
                String message = received.poll(1, TimeUnit.SECONDS);
                if (message != null) {
                    JsonNode node = mapper.readTree(message);
                    assertThat(node.get("issue").get("tenantId").asText()).isEqualTo("nyc311");
                    if (node.get("issue").get("id").asText().equals(id)) {
                        types.add(node.get("type").asText());
                    }
                }
            }
            assertThat(types).contains("CREATED", "ROUTED");
        } finally {
            session.close();
        }
    }

    // --- helpers ---

    private String createIssue(String tenant, Map<String, Object> body) {
        ResponseEntity<JsonNode> response = post("/api/tenants/" + tenant + "/issues", UUID.randomUUID().toString(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asText();
    }

    private JsonNode awaitStatus(String tenant, String id, IssueStatus status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                status.name().equals(get("/api/tenants/" + tenant + "/issues/" + id).get("issue").get("status").asText()));
        return get("/api/tenants/" + tenant + "/issues/" + id).get("issue");
    }

    private ResponseEntity<JsonNode> post(String path, String idempotencyKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode get(String path) {
        return http.getForObject(url(path), JsonNode.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
