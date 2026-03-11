package com.study.notification.producer.web;

import com.study.notification.domain.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 외부에서 알림 요청을 받을 때 사용하는 API 입력이다.
 *
 * @param channel 알림 채널
 * @param recipient 수신 대상
 * @param subject 제목
 * @param body 본문
 * @param templateCode 템플릿 코드
 */
public record NotificationRequest(
	@NotNull NotificationChannel channel,
	@NotBlank String recipient,
	@NotBlank String subject,
	@NotBlank String body,
	@NotBlank String templateCode
) {
}
