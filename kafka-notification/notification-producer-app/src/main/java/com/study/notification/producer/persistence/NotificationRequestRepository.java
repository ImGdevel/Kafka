package com.study.notification.producer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * notification_requests 테이블 접근 Repository.
 */
public interface NotificationRequestRepository extends JpaRepository<NotificationRequestEntity, String> {
}
