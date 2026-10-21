package dev.payflow.payment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.payflow.payment.outbox.EventPublisher;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);
    }

    @Bean
    @Primary
    RecordingEventPublisher recordingEventPublisher() {
        return new RecordingEventPublisher();
    }

    /** Captures published events so tests can assert on them. */
    public static class RecordingEventPublisher implements EventPublisher {

        public record Published(String key, String eventType, String payload) {
        }

        public final List<Published> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String key, String eventType, String payload) {
            published.add(new Published(key, eventType, payload));
        }
    }
}
