package com.study.redis;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.study.messaging.MessagePublisher;

/**
 * Redis Stream으로 메시지를 발행하는 구현체.
 */
@Service
@ConditionalOnProperty(name = "app.mq.type", havingValue = "redis")
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisStreamMessagePublisher implements MessagePublisher {

	private final StringRedisTemplate redisTemplate;
	private final String streamKey;

	public RedisStreamMessagePublisher(
		StringRedisTemplate redisTemplate,
		@Value("${app.redis.stream}") String streamKey
	) {
		this.redisTemplate = redisTemplate;
		this.streamKey = streamKey;
	}

	@Override
	public void publish(String message, String key) {
		Map<String, String> body = Map.of(
			"message", message,
			"key", key == null ? "" : key
		);
		redisTemplate.opsForStream().add(StreamRecords.newRecord()
			.in(streamKey)
			.ofMap(body)
			.withId(RecordId.autoGenerate()));
	}
}
