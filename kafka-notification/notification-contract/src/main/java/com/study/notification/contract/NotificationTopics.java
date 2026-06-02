package com.study.notification.contract;

/**
 * kafka-notification 실습에서 사용하는 토픽 이름 모음이다.
 */
public final class NotificationTopics {

	public static final String REQUESTED = "notification.requested";
	public static final String SENT = "notification.sent";
	public static final String FAILED = "notification.failed";
	public static final String REQUESTED_DLT = "notification.requested.dlt";

	private NotificationTopics() {
	}
}
