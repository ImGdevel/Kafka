package com.study.notification.worker.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * processed_notifications 테이블 접근 Repository.
 */
public interface ProcessedNotificationRepository extends JpaRepository<ProcessedNotificationEntity, String> {

	/**
	 * 멱등성 게이트: 이미 처리된 notificationId인지 확인한다.
	 */
	boolean existsByNotificationId(String notificationId);
}
