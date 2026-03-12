package com.study.notification.worker.persistence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.study.notification.domain.SentNotificationStore;

import lombok.RequiredArgsConstructor;

/**
 * Pillar 4 — DB 기반 멱등 전송 저장소.
 *
 * <p>processed_notifications 테이블을 영속 저장소로 활용한다.
 * JVM 재시작 후에도 이미 처리 완료된 notificationId를 인식할 수 있다.
 *
 * <p>보장 수준:
 * <ul>
 *   <li>within-session 재시도: in-memory 집합으로 즉시 차단한다.</li>
 *   <li>cross-restart 재전달: processed_notifications 테이블 조회로 차단한다.
 *       단, 해당 레코드는 {@code NotificationWorkerService}가 처리 완료 후 저장하므로,
 *       저장 전 크래시 시에는 Pillar 3 게이트가 최종 방어선으로 동작한다.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class DbSentNotificationStore implements SentNotificationStore {

	private final ProcessedNotificationRepository repository;
	// JVM 재시작 전까지 유효한 in-memory 캐시: DB 조회 없이 within-session 재시도를 즉시 차단한다.
	private final Set<String> inMemoryCache = ConcurrentHashMap.newKeySet();

	@Override
	public boolean hasSent(String notificationId) {
		// 1차: in-memory 캐시 (DB 조회 없이 빠르게 판단)
		if (inMemoryCache.contains(notificationId)) {
			return true;
		}
		// 2차: DB 조회 — 재시작 후에도 이미 처리된 알림을 인식한다
		return repository.existsByNotificationId(notificationId);
	}

	@Override
	public void recordSent(String notificationId) {
		// processed_notifications 기록은 NotificationWorkerService가 담당하므로,
		// 여기서는 in-memory 캐시에만 추가한다.
		inMemoryCache.add(notificationId);
	}
}
