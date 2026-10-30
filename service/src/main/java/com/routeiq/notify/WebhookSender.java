package com.routeiq.notify;

import com.routeiq.domain.Issue;
import com.routeiq.domain.Team;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookSender implements NotificationSender {

    private final RestClient client;

    public WebhookSender(RestClient.Builder builder, @Value("${routeiq.notifier.webhook.timeout:PT3S}") Duration timeout) {
        this.client = builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(timeout)
                        .withReadTimeout(timeout)))
                .build();
    }

    @Override
    public String channel() {
        return "WEBHOOK";
    }

    @Override
    public String target(Team team) {
        return team.webhookUrl();
    }

    @Override
    public void send(String target, String kind, Issue issue, Team team) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("team", team.slug());
        body.put("issue", issue);
        client.post().uri(target).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }
}
