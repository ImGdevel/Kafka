package com.study.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.study.messaging.MessagePublisher;

/**
 * RabbitMQ로 메시지를 발행하는 구현체.
 */
@Service
@ConditionalOnProperty(name = "app.mq.type", havingValue = "rabbit")
@ConditionalOnClass(RabbitTemplate.class)
public class RabbitMessagePublisher implements MessagePublisher {

	private final RabbitTemplate rabbitTemplate;
	private final String exchange;
	private final String defaultRoutingKey;

	public RabbitMessagePublisher(
		RabbitTemplate rabbitTemplate,
		@Value("${app.rabbit.exchange}") String exchange,
		@Value("${app.rabbit.routing-key:}") String defaultRoutingKey
	) {
		this.rabbitTemplate = rabbitTemplate;
		this.exchange = exchange;
		this.defaultRoutingKey = defaultRoutingKey;
	}

	@Override
	public void publish(String message, String key) {
		String routingKey = (key == null || key.isBlank()) ? defaultRoutingKey : key;
		rabbitTemplate.convertAndSend(exchange, routingKey, message);
	}
}
