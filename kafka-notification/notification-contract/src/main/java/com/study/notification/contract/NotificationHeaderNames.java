package com.study.notification.contract;

/**
 * Producer와 Worker가 공통으로 사용하는 Kafka header 이름이다.
 */
public final class NotificationHeaderNames {

	public static final String TRACE_ID = "trace-id";
	public static final String NOTIFICATION_ID = "notification-id";
	public static final String CHANNEL = "channel";

	private NotificationHeaderNames() {
	}
}
