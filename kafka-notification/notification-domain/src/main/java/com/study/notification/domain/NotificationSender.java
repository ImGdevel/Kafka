package com.study.notification.domain;

/**
 * 채널별 전송 구현이 따라야 하는 계약이다.
 */
public interface NotificationSender {

	/**
	 * 실제 알림 전송을 수행한다.
	 *
	 * @param target 수신 대상
	 * @param content 전송 본문
	 * @param template 전송에 사용한 템플릿 정보
	 * @return 전송 결과
	 */
	NotificationSendResult send(
		NotificationTarget target,
		NotificationContent content,
		NotificationTemplate template
	);

	/**
	 * 구현체가 담당하는 채널을 반환한다.
	 *
	 * @return 담당 채널
	 */
	NotificationChannel channel();
}
