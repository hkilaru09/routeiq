package com.routeiq.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes messages to the outbox table. Must run inside the transaction that made the state change. */
@Repository
public class Outbox {

    private final JdbcClient jdbc;
    private final Json json;

    public Outbox(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String key, Object payload) {
        jdbc.sql("INSERT INTO outbox (topic, msg_key, payload) VALUES (?, ?, ?::jsonb)")
                .params(topic, key, json.write(payload))
                .update();
    }
}
