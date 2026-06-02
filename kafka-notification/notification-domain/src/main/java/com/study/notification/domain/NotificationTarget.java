package com.study.notification.domain;

/**
 * 알림 수신 대상을 표현한다.
 *
 * @param recipient 수신자 식별자 또는 주소
 */
public record NotificationTarget(String recipient) {
}
