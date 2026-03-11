package com.study.rediscompare.service;

import com.study.messaging.MessagePublisher;
import com.study.rediscompare.web.MessageRequest;
import org.springframework.stereotype.Service;

/**
 * Redis Streams 비교 앱의 메시지 발행 책임을 분리한 서비스이다.
 */
@Service
public class RedisCompareMessageService {

	private final MessagePublisher messagePublisher;

	public RedisCompareMessageService(MessagePublisher messagePublisher) {
		this.messagePublisher = messagePublisher;
	}

	public void send(MessageRequest request) {
		messagePublisher.publish(request.message(), request.key());
	}
}
