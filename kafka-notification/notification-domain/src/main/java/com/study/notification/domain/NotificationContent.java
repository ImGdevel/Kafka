package com.study.notification.domain;

/**
 * 채널에 전달할 알림 본문이다.
 *
 * @param subject 제목 또는 요약
 * @param body 본문
 */
public record NotificationContent(String subject, String body) {
}
