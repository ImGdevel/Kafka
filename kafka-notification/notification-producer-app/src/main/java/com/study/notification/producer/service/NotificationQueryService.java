package com.study.notification.producer.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.producer.persistence.NotificationRequestEntity;
import com.study.notification.producer.persistence.NotificationRequestRepository;
import com.study.notification.producer.web.NotificationStatusResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 상태를 DB에서 조회 · 갱신하는 서비스.
 *
 * <p>기존 in-memory ConcurrentHashMap을 notification_requests 테이블로 대체한다.
 * Worker가 처리 결과를 notification.sent / notification.failed 토픽에 발행하면
 * NotificationResultListener가 해당 메서드를 호출하여 상태를 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

	private final NotificationRequestRepository requestRepository;

	@Transactional("transactionManager")
	public void markSent(NotificationSentEvent event) {
		requestRepository.findById(event.notificationId())
			.ifPresentOrElse(
				entity -> {
					// 개선 2: at-least-once 소비로 인한 재전달 시 멱등 처리.
					// ACCEPTED 상태일 때만 갱신하여 불필요한 중복 UPDATE를 방지한다.
					if (!"ACCEPTED".equals(entity.getStatus())) {
						log.debug("SENT 갱신 스킵: 이미 최종 상태 (notificationId={}, status={})",
							event.notificationId(), entity.getStatus());
						return;
					}
					entity.markSent(event.provider());
					log.info("알림 상태 갱신 → SENT: notificationId={}, provider={}", event.notificationId(), event.provider());
				},
				() -> log.warn("SENT 갱신 대상 없음 (notificationId={}): 이미 처리됐거나 존재하지 않는 알림입니다.", event.notificationId())
			);
	}

	@Transactional("transactionManager")
	public void markFailed(NotificationFailedEvent event) {
		requestRepository.findById(event.notificationId())
			.ifPresentOrElse(
				entity -> {
					// 개선 2: at-least-once 소비로 인한 재전달 시 멱등 처리.
					// ACCEPTED 상태일 때만 갱신하여 불필요한 중복 UPDATE를 방지한다.
					if (!"ACCEPTED".equals(entity.getStatus())) {
						log.debug("FAILED 갱신 스킵: 이미 최종 상태 (notificationId={}, status={})",
							event.notificationId(), entity.getStatus());
						return;
					}
					entity.markFailed(event.reason());
					log.info("알림 상태 갱신 → FAILED: notificationId={}, reason={}", event.notificationId(), event.reason());
				},
				() -> log.warn("FAILED 갱신 대상 없음 (notificationId={}): 이미 처리됐거나 존재하지 않는 알림입니다.", event.notificationId())
			);
	}

	@Transactional(value = "transactionManager", readOnly = true)
	public NotificationStatusResponse get(String notificationId) {
		NotificationRequestEntity entity = requestRepository.findById(notificationId)
			.orElseThrow(() -> new ResponseStatusException(
				HttpStatus.NOT_FOUND,
				"알림 조회 결과가 없습니다: " + notificationId
			));
		return NotificationStatusResponse.from(entity);
	}
}
