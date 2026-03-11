package com.study.messaging;

/**
 * 애플리케이션이 특정 메시지 브로커 구현에 직접 의존하지 않도록 감싸는 발행 경계이다.
 * 구현체는 Kafka, Redis Streams, RabbitMQ 등으로 교체될 수 있다.
 */
public interface MessagePublisher {

	/**
	 * 메시지를 발행한다.
	 *
	 * @param message 발행할 메시지 본문
	 * @param key 파티션 또는 라우팅 기준이 되는 키. 없으면 구현체 기본 정책을 따른다.
	 */
	void publish(String message, String key);
}
