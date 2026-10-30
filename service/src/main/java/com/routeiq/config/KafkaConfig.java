package com.routeiq.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    private final int partitions;

    public KafkaConfig(@Value("${routeiq.kafka.partitions:6}") int partitions) {
        this.partitions = partitions;
    }

    @Bean
    NewTopic issuesCreatedTopic() {
        return TopicBuilder.name(Topics.ISSUES_CREATED).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic issuesRoutedTopic() {
        return TopicBuilder.name(Topics.ISSUES_ROUTED).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic notificationsTopic() {
        return TopicBuilder.name(Topics.NOTIFICATIONS).partitions(partitions).replicas(1).build();
    }
}
