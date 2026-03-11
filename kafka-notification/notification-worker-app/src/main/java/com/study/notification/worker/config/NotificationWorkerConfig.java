package com.study.notification.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.study.notification.provider.sandbox.SandboxEmailSender;
import com.study.notification.provider.sandbox.SandboxSlackSender;

/**
 * Worker가 사용할 Sender 구현을 등록한다.
 */
@Configuration
public class NotificationWorkerConfig {

	@Bean
	public SandboxEmailSender sandboxEmailSender() {
		return new SandboxEmailSender();
	}

	@Bean
	public SandboxSlackSender sandboxSlackSender() {
		return new SandboxSlackSender();
	}
}
