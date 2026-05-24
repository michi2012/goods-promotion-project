package weverse.serverC.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import weverse.serverC.entity.BaseTimeEntity;

@Entity
@Table(
        name = "outbox_event",
        indexes = {
                @Index(name = "idx_outbox_created", columnList = "created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    public static OutboxEvent create(String aggregateId, String topic, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateId = aggregateId;
        event.topic = topic;
        event.payload = payload;
        return event;
    }
}