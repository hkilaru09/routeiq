package com.routeiq.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Rows are locked with SKIP LOCKED so several relays can run side by side, and a row
 * is only marked published after the broker acknowledges it. Delivery is at-least-once; consumers are idempotent.
 */
@Component
@ConditionalOnProperty(name = "routeiq.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private record Row(long id, String topic, String key, String payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       MeterRegistry meters, @Value("${routeiq.outbox.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.meters = meters;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${routeiq.outbox.poll-interval-ms:50}")
    public void relay() {
        Integer published;
        do {
            published = tx.execute(status -> relayBatch());
        } while (published != null && published == batchSize);
    }

    int relayBatch() {
        List<Row> rows = jdbc.sql("""
                        SELECT id, topic, msg_key, payload::text AS payload FROM outbox
                        WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                        """)
                .param(batchSize)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("msg_key"), rs.getString("payload")))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
        for (Row row : rows) {
            sends.add(kafka.send(row.topic(), row.key(), row.payload()));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Roll back: nothing is marked published, so the whole batch is retried on the next poll.
            meters.counter("routeiq.outbox.publish.failures").increment();
            throw new IllegalStateException("outbox publish failed", e);
        }
        Long[] ids = rows.stream().map(Row::id).toArray(Long[]::new);
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(?)").param(ids).update();
        meters.counter("routeiq.outbox.published").increment(rows.size());
        return rows.size();
    }

    /** Published rows are only kept for a day, for debugging. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purge() {
        jdbc.sql("DELETE FROM outbox WHERE published_at < now() - interval '1 day'").update();
    }
}
