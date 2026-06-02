package com.study.notification.domain;

/**
 * 채널 전송 결과를 표현한다.
 *
 * @param channel 전송 채널
 * @param success 성공 여부
 * @param detail 부가 설명
 */
public record NotificationSendResult(
	NotificationChannel channel,
	boolean success,
	String detail
) {
}
