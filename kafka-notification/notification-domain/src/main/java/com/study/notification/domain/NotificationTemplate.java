package com.study.notification.domain;

/**
 * 렌더링 전 템플릿 식별 정보이다.
 *
 * @param templateCode   템플릿 코드
 * @param notificationId 알림 식별자 (멱등 전송 dedup 키)
 */
public record NotificationTemplate(String templateCode, String notificationId) {
}
