package com.study.notification.producer.web;

/**
 * 알림 요청 접수 결과이다.
 *
 * @param notificationId 알림 식별자
 * @param traceId 추적 식별자
 * @param status 접수 상태
 */
public record NotificationAcceptedResponse(
	String notificationId,
	String traceId,
	String status
) {
}
