package com.study.notification.provider.sandbox;

import com.study.notification.domain.NotificationChannel;
import com.study.notification.domain.NotificationContent;
import com.study.notification.domain.NotificationSendResult;
import com.study.notification.domain.NotificationSender;
import com.study.notification.domain.NotificationTarget;
import com.study.notification.domain.NotificationTemplate;
import com.study.notification.domain.SentNotificationStore;

/**
 * Slack 채널의 샌드박스 전송기 스텁이다.
 *
 * <p>Pillar 4 (개선) — 멱등 전송: {@link SentNotificationStore}를 통해 중복 전송을 억제한다.
 * 저장소 구현체에 따라 within-session 또는 cross-restart 수준의 dedup을 제공한다.
 * 동일 notificationId로 재시도가 들어와도 실제 전송은 1회만 수행한다.
 */
public class SandboxSlackSender implements NotificationSender {

	private final SentNotificationStore sentStore;

	public SandboxSlackSender(SentNotificationStore sentStore) {
		this.sentStore = sentStore;
	}

	@Override
	public NotificationSendResult send(
		NotificationTarget target,
		NotificationContent content,
		NotificationTemplate template
	) {
		throwIfForcedToFail(template);

		// Pillar 4: 이미 전송된 notificationId → 스킵
		if (template.notificationId() != null && sentStore.hasSent(template.notificationId())) {
			return new NotificationSendResult(channel(), true, "SLACK 중복 전송 스킵 (캐시 반환)");
		}

		sentStore.recordSent(template.notificationId());
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
