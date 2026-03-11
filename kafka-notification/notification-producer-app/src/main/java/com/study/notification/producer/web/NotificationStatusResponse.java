package com.study.notification.producer.web;

import com.study.notification.domain.NotificationChannel;
import com.study.notification.producer.service.NotificationQuerySnapshot;
import com.study.notification.producer.service.NotificationQueryStatus;

/**
 * 알림 조회 API 응답이다.
 *
 * @param notificationId 알림 식별자
 * @param traceId 추적 식별자
 * @param channel 알림 채널
 * @param recipient 수신 대상
 * @param subject 제목
 * @param templateCode 템플릿 코드
 * @param status 현재 상태
 * @param provider 성공 시 provider 이름
 * @param reason 실패 시 사유
 */
public record NotificationStatusResponse(
	String notificationId,
	String traceId,
	NotificationChannel channel,
	String recipient,
	String subject,
	String templateCode,
	NotificationQueryStatus status,
	String provider,
	String reason
) {

	public static NotificationStatusResponse from(NotificationQuerySnapshot snapshot) {
		return new NotificationStatusResponse(
			snapshot.notificationId(),
			snapshot.traceId(),
			snapshot.channel(),
			snapshot.recipient(),
			snapshot.subject(),
			snapshot.templateCode(),
			snapshot.status(),
			snapshot.provider(),
			snapshot.reason()
		);
	}
}
