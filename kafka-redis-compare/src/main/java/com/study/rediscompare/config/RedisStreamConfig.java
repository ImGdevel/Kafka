package com.study.rediscompare.config;

import java.time.Duration;
import java.util.UUID;

import com.study.rediscompare.listener.RedisStreamListener;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableScheduling
public class RedisStreamConfig {

	private static final String GROUP_NAME = "redis-demo-group";
	private static final long POLL_DELAY_MILLIS = 1000L;
	private static final int READ_COUNT = 10;
	private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(1);

	private final String consumerName = "poller-" + UUID.randomUUID();
	private final StringRedisTemplate redisTemplate;
	private final RedisStreamListener listener;
	private final String streamKey;

	public RedisStreamConfig(
		StringRedisTemplate redisTemplate,
		RedisStreamListener listener,
		@Value("${app.redis.stream}") String streamKey
	) {
		this.redisTemplate = redisTemplate;
		this.listener = listener;
		this.streamKey = streamKey;
	}

	@Bean
	InitializingBean createGroupIfNotExists() {
		return this::ensureConsumerGroupExists;
	}

	@Scheduled(fixedDelay = POLL_DELAY_MILLIS)
	void pollStream() {
		var records = readRecords();
		if (records == null || records.isEmpty()) {
			return;
		}
		records.forEach(this::handleRecord);
	}

	private void ensureConsumerGroupExists() {
		try {
			redisTemplate.opsForStream().createGroup(streamKey, GROUP_NAME);
		} catch (Exception ignore) {
			// 학습 환경에서는 이미 그룹이 있으면 그대로 진행한다.
		}
	}

	private java.util.List<MapRecord<String, Object, Object>> readRecords() {
		return redisTemplate.opsForStream().read(
			Consumer.from(GROUP_NAME, consumerName),
			StreamReadOptions.empty().count(READ_COUNT).block(BLOCK_TIMEOUT),
			StreamOffset.create(streamKey, ReadOffset.lastConsumed())
		);
	}

	private void handleRecord(MapRecord<String, Object, Object> record) {
		listener.onMessage(record);
		acknowledge(record);
	}

	private void acknowledge(MapRecord<String, Object, Object> record) {
		redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, record.getId());
	}
}
