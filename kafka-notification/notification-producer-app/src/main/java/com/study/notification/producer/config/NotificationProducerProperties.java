package com.study.notification.producer.config;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.study.notification.domain.NotificationChannel;

/**
 * Producer 앱의 입력 채널 정책을 보관한다.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "app.notification.producer")
public class NotificationProducerProperties {

	private Set<NotificationChannel> allowedChannels = EnumSet.of(
		NotificationChannel.EMAIL,
		NotificationChannel.SLACK
	);

}
