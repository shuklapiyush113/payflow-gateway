package dev.payflow.payment.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks a batch of unpublished events. {@code SKIP LOCKED} lets several service replicas run the
     * relay at once without publishing the same event twice or blocking each other.
     */
    @Query(value = """
            select * from outbox_events
            where published_at is null and attempts < :maxAttempts
            order by created_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(int batchSize, int maxAttempts);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(String aggregateId);
}
