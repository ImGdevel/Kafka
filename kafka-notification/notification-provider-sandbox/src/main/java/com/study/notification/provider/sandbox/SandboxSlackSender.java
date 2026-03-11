package com.study.notification.provider.sandbox;

import com.study.notification.domain.NotificationChannel;
import com.study.notification.domain.NotificationContent;
import com.study.notification.domain.NotificationSendResult;
import com.study.notification.domain.NotificationSender;
import com.study.notification.domain.NotificationTarget;
import com.study.notification.domain.NotificationTemplate;

/**
 * Slack 채널의 샌드박스 전송기 스텁이다.
 */
public class SandboxSlackSender implements NotificationSender {

	@Override
	public NotificationSendResult send(
		NotificationTarget target,
		NotificationContent content,
		NotificationTemplate template
	) {
		throwIfForcedToFail(template);
		return new NotificationSendResult(channel(), true, "SLACK 샌드박스 스텁");
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.SLACK;
	}

	private void throwIfForcedToFail(NotificationTemplate template) {
		if ("FAIL_ALWAYS".equals(template.templateCode())) {
			throw new IllegalStateException("FAIL_ALWAYS 템플릿은 SLACK 전송 실패를 강제합니다.");
		}
	}
}
