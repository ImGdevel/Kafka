package com.study.notification.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.study.notification.domain.SentNotificationStore;
import com.study.notification.provider.sandbox.SandboxEmailSender;
import com.study.notification.provider.sandbox.SandboxSlackSender;
import com.study.notification.worker.persistence.DbSentNotificationStore;
import com.study.notification.worker.persistence.ProcessedNotificationRepository;

/**
 * Worker가 사용할 Sender 구현과 Pillar 4 저장소를 등록한다.
 */
@Configuration
public class NotificationWorkerConfig {

	/**
	 * Pillar 4 (개선) — DB 기반 멱등 전송 저장소.
	 * processed_notifications 테이블을 영속 저장소로 사용하여
	 * Worker 재시작 후에도 이미 처리된 알림에 대한 dedup을 제공한다.
	 */
	@Bean
	public SentNotificationStore sentNotificationStore(ProcessedNotificationRepository repository) {
		return new DbSentNotificationStore(repository);
	}

	@Bean
	public SandboxEmailSender sandboxEmailSender(SentNotificationStore sentNotificationStore) {
		return new SandboxEmailSender(sentNotificationStore);
	}

	@Bean
	public SandboxSlackSender sandboxSlackSender(SentNotificationStore sentNotificationStore) {
		return new SandboxSlackSender(sentNotificationStore);
	}
}
