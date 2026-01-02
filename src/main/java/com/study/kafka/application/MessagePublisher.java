package com.study.kafka.application;

public interface MessagePublisher {

	void publish(String message, String key);
}

