package com.study.notification.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class NotificationProducerApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationProducerApplication.class, args);
	}
}
