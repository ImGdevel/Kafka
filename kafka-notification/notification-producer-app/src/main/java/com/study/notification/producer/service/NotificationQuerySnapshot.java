package com.study.notification.producer.service;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.domain.NotificationChannel;

/**
 * 조회 API가 읽는 in-memory 상태 스냅샷이다.
 *
 * @param notificationId 알림 식별자
 * @param traceId 추적 식별자
 * @param channel 알림 채널
 * @param recipient 수신 대상
 * @param subject 제목
 * @param templateCode 템플릿 코드
 * @param status 현재 상태
 * @param provider 성공 시 사용한 provider 이름
 * @param reason 실패 시 사유
 */
public record NotificationQuerySnapshot(
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

	public static NotificationQuerySnapshot accepted(NotificationRequestedEvent event) {
		return new NotificationQuerySnapshot(
			event.notificationId(),
			event.traceId(),
			event.channel(),
			event.recipient(),
			event.subject(),
			event.templateCode(),
			NotificationQueryStatus.ACCEPTED,
			null,
			null
		);
	}

	public static NotificationQuerySnapshot sentOnly(NotificationSentEvent event) {
		return new NotificationQuerySnapshot(
			event.notificationId(),
			event.traceId(),
			event.channel(),
			null,
			null,
			null,
			NotificationQueryStatus.SENT,
			event.provider(),
			null
		);
	}

	public static NotificationQuerySnapshot failedOnly(NotificationFailedEvent event) {
		return new NotificationQuerySnapshot(
			event.notificationId(),
			event.traceId(),
			event.channel(),
			null,
			null,
			null,
			NotificationQueryStatus.FAILED,
			null,
			event.reason()
		);
	}

	public NotificationQuerySnapshot markSent(String providerName) {
		return new NotificationQuerySnapshot(
			notificationId,
			traceId,
			channel,
			recipient,
			subject,
			templateCode,
			NotificationQueryStatus.SENT,
			providerName,
			null
		);
	}

	public NotificationQuerySnapshot markFailed(String failureReason) {
		return new NotificationQuerySnapshot(
			notificationId,
			traceId,
			channel,
			recipient,
			subject,
			templateCode,
			NotificationQueryStatus.FAILED,
			null,
			failureReason
		);
	}
}
