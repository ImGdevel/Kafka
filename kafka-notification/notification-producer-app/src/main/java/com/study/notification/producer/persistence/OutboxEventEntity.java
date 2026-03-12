package com.study.notification.producer.persistence;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * outbox_events 테이블 엔티티.
 *
 * <p>Pillar 1 — Outbox Pattern: HTTP 요청과 동일한 DB 트랜잭션 안에서 저장되며,
 * OutboxRelay가 주기적으로 읽어 Kafka에 발행한 뒤 published=true로 표시한다.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "notification_id", nullable = false)
	private String notificationId;

	@Column(name = "topic", nullable = false)
	private String topic;

	@Column(name = "payload", nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Column(name = "published", nullable = false)
	private boolean published;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	/**
	 * 미발행 Outbox 이벤트를 생성한다.
	 */
	public static OutboxEventEntity of(String notificationId, String topic, String payload) {
		OutboxEventEntity entity = new OutboxEventEntity();
		entity.notificationId = notificationId;
		entity.topic = topic;
		entity.payload = payload;
		entity.published = false;
		entity.createdAt = LocalDateTime.now();
		return entity;
	}

	/**
	 * Kafka 발행 완료 후 호출하여 published=true로 표시한다.
	 */
	public void markPublished() {
		this.published = true;
		this.publishedAt = LocalDateTime.now();
	}
}
