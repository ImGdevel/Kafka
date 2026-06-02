package com.study.notification.contract;

import com.study.notification.domain.NotificationChannel;

/**
 * 전송 실패 시 발행하는 이벤트이다.
 *
 * @param notificationId 알림 식별자
 * @param traceId 추적 식별자
 * @param channel 전송 채널
 * @param reason 실패 이유
 */
public record NotificationFailedEvent(
	String notificationId,
	String traceId,
	NotificationChannel channel,
	String reason
) {
}
