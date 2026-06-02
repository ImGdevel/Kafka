package com.study.rediscompare.listener;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamListener implements StreamListener<String, MapRecord<String, ?, ?>> {

	private static final Logger log = LoggerFactory.getLogger(RedisStreamListener.class);

	@Override
	public void onMessage(MapRecord<String, ?, ?> message) {
		Map<?, ?> body = message.getValue();
		log.info("Redis Stream 수신: stream={}, id={}, body={}", message.getStream(), message.getId(), body);
	}
}
