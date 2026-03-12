package com.study.notification.domain;

/**
 * Pillar 4 — 멱등 전송 저장소 인터페이스.
 *
 * <p>동일 notificationId에 대한 중복 전송을 방지하기 위해 "이미 전송됨" 여부를 조회·기록한다.
 *
 * <p>구현체는 보장 수준에 따라 선택한다:
 * <ul>
 *   <li>{@code InMemorySentNotificationStore} — JVM 내 ConcurrentHashMap. 재시작 시 초기화된다.</li>
 *   <li>{@code DbSentNotificationStore} — processed_notifications 테이블 기반. 재시작 후에도 유지된다.</li>
 *   <li>Redis {@code SET} 기반 구현 — 분산 환경에서 cross-instance dedup을 보장한다.</li>
 * </ul>
 */
public interface SentNotificationStore {

	/**
	 * 주어진 notificationId가 이미 전송된 것으로 기록되어 있으면 {@code true}를 반환한다.
	 */
	boolean hasSent(String notificationId);

	/**
	 * 주어진 notificationId를 "전송됨"으로 기록한다.
	 */
	void recordSent(String notificationId);
}
