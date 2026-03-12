package com.study.notification.producer.persistence;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.domain.NotificationChannel;

/**
 * notification_requests 테이블 엔티티.
 *
 * <p>HTTP 요청 접수 시 DB에 저장되며, Outbox 릴레이 이후 Worker의 처리 결과에 따라
 * status가 ACCEPTED → SENT | FAILED 로 전이된다.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "notification_requests")
public class NotificationRequestEntity {

	@Id
	@Column(name = "notification_id")
	private String notificationId;

	@Column(name = "trace_id", nullable = false)
	private String traceId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false)
	private NotificationChannel channel;

	@Column(name = "recipient", nullable = false)
	private String recipient;

	@Column(name = "subject", nullable = false)
	private String subject;

	@Column(name = "body", nullable = false, columnDefinition = "TEXT")
	private String body;

	@Column(name = "template_code", nullable = false)
	private String templateCode;

	/** ACCEPTED | SENT | FAILED */
	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "provider")
	private String provider;

	@Column(name = "reason", columnDefinition = "TEXT")
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * HTTP 요청 접수 시 ACCEPTED 상태의 엔티티를 생성한다.
	 */
	public static NotificationRequestEntity accepted(NotificationRequestedEvent event) {
		NotificationRequestEntity entity = new NotificationRequestEntity();
		entity.notificationId = event.notificationId();
		entity.traceId = event.traceId();
		entity.channel = event.channel();
		entity.recipient = event.recipient();
		entity.subject = event.subject();
		entity.body = event.body();
		entity.templateCode = event.templateCode();
		entity.status = "ACCEPTED";
		entity.createdAt = LocalDateTime.now();
		entity.updatedAt = LocalDateTime.now();
		return entity;
	}

	/**
	 * Worker 성공 결과를 반영하여 SENT 상태로 전이한다.
	 */
	public NotificationRequestEntity markSent(String providerName) {
		this.status = "SENT";
		this.provider = providerName;
		this.updatedAt = LocalDateTime.now();
		return this;
	}

	/**
	 * Worker 실패 결과를 반영하여 FAILED 상태로 전이한다.
	 */
	public NotificationRequestEntity markFailed(String failureReason) {
		this.status = "FAILED";
		this.reason = failureReason;
		this.updatedAt = LocalDateTime.now();
		return this;
	}
}
