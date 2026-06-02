package com.study.notification.contract;

import com.study.notification.domain.NotificationChannel;

/**
 * Producer가 Worker로 전달하는 알림 요청 이벤트이다.
 *
 * @param notificationId 알림 식별자
 * @param traceId 추적 식별자
 * @param channel 알림 채널
 * @param recipient 수신 대상
 * @param subject 제목
 * @param body 본문
 * @param templateCode 템플릿 코드
 */
public record NotificationRequestedEvent(
	String notificationId,
	String traceId,
	NotificationChannel channel,
	String recipient,
	String subject,
	String body,
	String templateCode
) {
}
