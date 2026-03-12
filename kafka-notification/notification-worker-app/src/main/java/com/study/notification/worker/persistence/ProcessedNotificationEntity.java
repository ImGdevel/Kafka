package com.study.notification.worker.persistence;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * processed_notifications 테이블 엔티티.
 *
 * <p>Pillar 3 — 멱등 소비: Worker가 이미 처리한 notificationId를 기록한다.
 * 동일 메시지가 재전달될 경우 이 테이블에 레코드가 있으면 처리를 스킵한다.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "processed_notifications")
public class ProcessedNotificationEntity {

	@Id
	@Column(name = "notification_id")
	private String notificationId;

	/** SENT | FAILED */
	@Column(name = "result", nullable = false)
	private String result;

	@Column(name = "provider")
	private String provider;

	@Column(name = "reason", columnDefinition = "TEXT")
	private String reason;

	@Column(name = "processed_at", nullable = false, updatable = false)
	private LocalDateTime processedAt;

	/**
	 * 전송 성공 처리 결과를 생성한다.
	 */
	public static ProcessedNotificationEntity sent(String notificationId, String provider) {
		ProcessedNotificationEntity entity = new ProcessedNotificationEntity();
		entity.notificationId = notificationId;
		entity.result = "SENT";
		entity.provider = provider;
		entity.processedAt = LocalDateTime.now();
		return entity;
	}

	/**
	 * 전송 실패 처리 결과를 생성한다.
	 */
	public static ProcessedNotificationEntity failed(String notificationId, String reason) {
		ProcessedNotificationEntity entity = new ProcessedNotificationEntity();
		entity.notificationId = notificationId;
		entity.result = "FAILED";
		entity.reason = reason;
		entity.processedAt = LocalDateTime.now();
		return entity;
	}
}
