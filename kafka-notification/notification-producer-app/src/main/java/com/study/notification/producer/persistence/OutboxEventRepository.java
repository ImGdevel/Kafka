package com.study.notification.producer.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

/**
 * outbox_events 테이블 접근 Repository.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

	/**
	 * 미발행 이벤트를 비관적 락으로 조회한다.
	 *
	 * <p>Pageable로 batchSize를 제한하여 한 번에 처리할 레코드 수를 조정한다.
	 * PESSIMISTIC_WRITE 락은 동시 릴레이 실행 시 중복 발행을 방지한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM OutboxEventEntity o WHERE o.published = false ORDER BY o.id ASC")
	List<OutboxEventEntity> findUnpublishedWithLock(Pageable pageable);
}
