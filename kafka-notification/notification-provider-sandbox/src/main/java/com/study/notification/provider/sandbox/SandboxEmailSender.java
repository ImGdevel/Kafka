package com.study.notification.provider.sandbox;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.study.notification.domain.NotificationChannel;
import com.study.notification.domain.NotificationContent;
import com.study.notification.domain.NotificationSendResult;
import com.study.notification.domain.NotificationSender;
import com.study.notification.domain.NotificationTarget;
import com.study.notification.domain.NotificationTemplate;

/**
 * Email 채널의 샌드박스 전송기 스텁이다.
 *
 * <p>Pillar 4 — 멱등 전송: notificationId 기준 ConcurrentHashMap으로 중복 전송을 억제한다.
 * 동일 notificationId로 재시도가 들어와도 실제 전송은 1회만 수행하고 캐시된 결과를 반환한다.
 */
public class SandboxEmailSender implements NotificationSender {

	private final Set<String> sentIds = ConcurrentHashMap.newKeySet();

	@Override
	public NotificationSendResult send(
		NotificationTarget target,
		NotificationContent content,
		NotificationTemplate template
	) {
		throwIfForcedToFail(template);

		// Pillar 4: 이미 전송된 notificationId → 캐시 결과 반환
		if (template.notificationId() != null && !sentIds.add(template.notificationId())) {
			return new NotificationSendResult(channel(), true, "EMAIL 중복 전송 스킵 (캐시 반환)");
		}

		return new NotificationSendResult(channel(), true, "EMAIL 샌드박스 스텁");
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.EMAIL;
	}

	private void throwIfForcedToFail(NotificationTemplate template) {
		if ("FAIL_ALWAYS".equals(template.templateCode())) {
			throw new IllegalStateException("FAIL_ALWAYS 템플릿은 EMAIL 전송 실패를 강제합니다.");
		}
	}
}
