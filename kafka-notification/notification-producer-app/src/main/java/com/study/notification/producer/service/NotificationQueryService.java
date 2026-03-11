package com.study.notification.producer.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.producer.web.NotificationStatusResponse;

/**
 * 알림 상태를 메모리에 projection 하여 조회 API에 제공한다.
 */
@Service
public class NotificationQueryService {

	private final Map<String, NotificationQuerySnapshot> snapshots = new ConcurrentHashMap<>();

	public void registerAccepted(NotificationRequestedEvent event) {
		snapshots.put(event.notificationId(), NotificationQuerySnapshot.accepted(event));
	}

	public void markSent(NotificationSentEvent event) {
		snapshots.compute(
			event.notificationId(),
			(notificationId, current) -> current == null
				? NotificationQuerySnapshot.sentOnly(event)
				: current.markSent(event.provider())
		);
	}

	public void markFailed(NotificationFailedEvent event) {
		snapshots.compute(
			event.notificationId(),
			(notificationId, current) -> current == null
				? NotificationQuerySnapshot.failedOnly(event)
				: current.markFailed(event.reason())
		);
	}

	public NotificationStatusResponse get(String notificationId) {
		NotificationQuerySnapshot snapshot = snapshots.get(notificationId);
		if (snapshot == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "알림 조회 결과가 없습니다: " + notificationId);
		}
		return NotificationStatusResponse.from(snapshot);
	}
}
