package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.TransactionListing;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 05 — Spring Kafka 트랜잭션 추상화
 *
 * 검증 명제: "Spring Kafka의 트랜잭션은 Lab01~04에서 본 것과 같은 메커니즘 위에 있다 —
 *            추상화가 새로운 보장을 추가하지 않는다"
 *
 * Lab01~04는 raw KafkaProducer로 beginTransaction() / commitTransaction() / abortTransaction()을
 * 직접 호출했다. Spring은 이 호출들을 KafkaTemplate과 KafkaTransactionManager 뒤로 감춘다.
 * 감춘 뒤에도 브로커 관점에서는 아무것도 달라지지 않는다 — 똑같은 transactional.id가 코디네이터에 등록되고,
 * 똑같은 epoch로 펜싱되고, 똑같은 control batch(커밋/중단 마커)가 로그에 쌓인다.
 * 즉 Spring이 주는 것은 "보장"이 아니라 "생명주기 관리"다. 보장은 여전히 브로커가 준다.
 *
 * Q1. KafkaTemplate.executeInTransaction(...) — 콜백이 정상 종료하면 커밋되어 read_committed에 보인다.
 *     콜백이 예외를 던지면 Spring이 대신 abortTransaction()을 호출하므로 read_committed에는 안 보이고,
 *     read_uncommitted에는 그대로 보인다. (= Lab00/Lab02에서 raw Producer로 본 결과와 완전히 동일하다)
 * Q2. setTransactionIdPrefix(prefix)로 준 접두사가 실제로 브로커 코디네이터에 등록되는지 확인한다.
 *     AdminClient.listTransactions()에 그 prefix로 시작하는 transactional.id가 나타나야 한다.
 *     Spring이 만드는 것도 결국 Lab01에서 손으로 준 그 transactional.id다.
 * Q3. KafkaTransactionManager + TransactionTemplate 경로 — @Transactional 메서드가 내부적으로 타는 길이다.
 *     execute(...) 안에서 send를 여러 건 하고 마지막에 RuntimeException을 던지면 전부 롤백되어
 *     read_committed에 하나도 안 보인다. 예외 없이 끝나면 전부 보인다. 원자 단위는 여전히 "트랜잭션 하나"다.
 *
 * 왜 Spring 컨텍스트를 띄우지 않는가:
 *   이 모듈은 kafka-connect 모듈과 마찬가지로 src/main이 없는 테스트 전용 모듈이다.
 *   @SpringBootTest는 패키지를 거슬러 올라가며 @SpringBootConfiguration을 찾는데 여기엔 그런 클래스가 없어
 *   IllegalStateException으로 깨진다. 게다가 이 Lab의 관심사는 "DI 설정이 어떻게 생겼는가"가 아니라
 *   "Spring의 트랜잭션 객체들이 브로커에 무엇을 하는가"이므로, 컨텍스트 없이 객체를 직접 new 하는 편이
 *   검증 대상이 더 선명하게 드러난다. 실무 설정에서는 이 객체들을 @Bean으로 등록할 뿐 동작은 같다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab05*' --info
 */
@Tag("lab")
@DisplayName("Lab 05 — Spring 트랜잭션 추상화")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab05SpringTransactionTest {

    // 커밋 시나리오와 중단 시나리오가 서로의 검증을 오염시키지 않도록 토픽을 완전히 분리한다.
    private static final String Q1_COMMIT   = "tx-lab05-q1-commit";
    private static final String Q1_ABORT    = "tx-lab05-q1-abort";
    private static final String Q2_TOPIC    = "tx-lab05-q2-txid";
    private static final String Q3_ROLLBACK = "tx-lab05-q3-rollback";
    private static final String Q3_COMMIT   = "tx-lab05-q3-commit";

    private static final List<String> ALL_TOPICS =
            List.of(Q1_COMMIT, Q1_ABORT, Q2_TOPIC, Q3_ROLLBACK, Q3_COMMIT);

    // Spring은 "prefix"만 받고 실제 transactional.id는 prefix + 접미사로 스스로 만든다.
    // 접미사 형태는 버전 구현 세부사항이므로 여기서는 prefix로 시작한다는 것만 단정한다(Q2 참고).
    private static final String Q1_TX_PREFIX = "tx-lab05-q1-";
    private static final String Q2_TX_PREFIX = "tx-lab05-q2-";
    private static final String Q3_TX_PREFIX = "tx-lab05-q3-";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 05: Spring Kafka 트랜잭션 추상화",
                "Spring으로 감싸도 브로커가 보는 것은 Lab01~04와 똑같은 트랜잭션이다");
        for (String topic : ALL_TOPICS) {
            createTopic(topic, 1, (short) 3);
        }
    }

    @AfterAll
    static void tearDown() {
        for (String topic : ALL_TOPICS) {
            deleteTopic(topic);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Q1. executeInTransaction — 정상 종료는 커밋, 예외는 abort로 이어진다")
    void executeInTransactionCommitsOnReturnAndAbortsOnException() {
        DefaultKafkaProducerFactory<String, String> factory = producerFactory(Q1_TX_PREFIX);
        try {
            // 주의: KafkaTemplate은 생성자에서 producerFactory.transactionCapable()을 한 번 읽어 캐시한다.
            //       따라서 setTransactionIdPrefix()는 반드시 KafkaTemplate을 만들기 전에 호출되어 있어야 한다.
            //       (producerFactory() 헬퍼가 이미 그렇게 해 두었다.)
            KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);

            // ── 정상 경로 ──────────────────────────────────────────────
            // 콜백이 값을 반환하며 끝나면 Spring이 commitTransaction()을 호출한다.
            // 콜백 인자로 들어오는 operations는 이 트랜잭션에 묶인 Producer를 쓰는 KafkaTemplate 자신이다.
            String result = template.executeInTransaction(operations -> {
                operations.send(Q1_COMMIT, "spring-1", "커밋될 메시지 1");
                operations.send(Q1_COMMIT, "spring-2", "커밋될 메시지 2");
                return "done";
            });

            List<ConsumerRecord<String, String>> committed =
                    readCommitted(Q1_COMMIT, "lab05-q1-commit-" + System.nanoTime(), 2, 5000);

            printRecords("정상 종료 후 read_committed", committed);
            System.out.printf("  콜백 반환값이 그대로 전달된다: %s%n", result);

            assertThat(result)
                    .as("executeInTransaction은 콜백의 반환값을 그대로 돌려준다")
                    .isEqualTo("done");
            assertThat(committed)
                    .as("콜백이 정상 종료하면 Spring이 commitTransaction()을 호출한다")
                    .hasSize(2);

            // ── 예외 경로 ──────────────────────────────────────────────
            // 콜백에서 던진 예외는 Spring이 abortTransaction()을 호출한 뒤 호출부로 그대로 전파한다.
            // flush()로 브로커 로그에 먼저 기록시킨다 — abort는 로그에서 지우는 게 아니라
            // read_committed 소비자가 걸러내는 것임을 보이기 위함이다(Lab02 Q2와 같은 장치).
            KafkaOperations.OperationsCallback<String, String, Void> failing = operations -> {
                operations.send(Q1_ABORT, "spring-x", "중단될 메시지");
                operations.flush();
                throw new IllegalStateException("업무 로직 실패");
            };

            assertThatThrownBy(() -> template.executeInTransaction(failing))
                    .as("콜백의 예외는 abort 처리 후 호출부로 전파된다")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("업무 로직 실패");

            // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다.
            List<ConsumerRecord<String, String>> afterAbortCommitted =
                    readCommitted(Q1_ABORT, "lab05-q1-abort-c-" + System.nanoTime(), 99, 4000);
            List<ConsumerRecord<String, String>> afterAbortUncommitted =
                    readUncommitted(Q1_ABORT, "lab05-q1-abort-u-" + System.nanoTime(), 1, 5000);

            printRecords("중단 후 read_committed", afterAbortCommitted);
            printRecords("중단 후 read_uncommitted", afterAbortUncommitted);
            System.out.printf("  중단된 토픽 LSO=%d, HW=%d%n",
                    lastStableOffset(Q1_ABORT, 0), highWatermark(Q1_ABORT, 0));
            printSeparator();

            assertThat(afterAbortCommitted)
                    .as("중단된 트랜잭션의 메시지는 read_committed에 보이지 않는다")
                    .isEmpty();
            assertThat(afterAbortUncommitted)
                    .as("로그에는 물리적으로 남아 있다 — 지워진 게 아니라 걸러진 것이다")
                    .hasSize(1);
        } finally {
            // DefaultKafkaProducerFactory는 DisposableBean이다. 스프링 컨텍스트가 없으니 직접 정리한다.
            // 안 하면 열린 Producer와 그 백그라운드 sender 스레드가 그대로 남는다.
            factory.destroy();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Q2. setTransactionIdPrefix로 준 접두사가 브로커 코디네이터에 그대로 등록된다")
    void transactionIdPrefixIsRegisteredOnBroker() throws Exception {
        DefaultKafkaProducerFactory<String, String> factory = producerFactory(Q2_TX_PREFIX);
        try {
            KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);

            assertThat(factory.getTransactionIdPrefix())
                    .as("prefix를 주지 않으면 KafkaTemplate이 트랜잭션 모드로 동작하지 않는다")
                    .isEqualTo(Q2_TX_PREFIX);

            // ProducerFactory는 실제로 필요해질 때까지 Producer를 만들지 않는다.
            // 즉 트랜잭션을 한 번 수행해야 비로소 코디네이터에 transactional.id가 등록된다.
            KafkaOperations.OperationsCallback<String, String, Void> probe = operations -> {
                operations.send(Q2_TOPIC, "probe", "transactional.id 등록을 유발하는 메시지");
                return null;
            };
            template.executeInTransaction(probe);

            // listTransactions()는 진행 중인 것뿐 아니라 최근 종료된 트랜잭션도 함께 반환한다.
            // (코디네이터가 transactional.id.expiration.ms 동안 상태를 들고 있기 때문이다.)
            // 그래서 커밋이 끝난 뒤에 조회해도 방금 쓴 id가 보인다.
            List<TransactionListing> mine = findTransactionsWithPrefix(Q2_TX_PREFIX);

            System.out.println("  브로커에 등록된 transactional.id:");
            for (TransactionListing listing : mine) {
                System.out.printf("      %s  (state=%s, producerId=%d)%n",
                        listing.transactionalId(), listing.state(), listing.producerId());
            }
            System.out.println("  → prefix는 우리가 정하고, 접미사는 Spring이 붙인다.");
            System.out.println("     Spring Kafka 3.x는 EOSMode.V2(KIP-447)가 기본이라");
            System.out.println("     파티션마다 Producer를 만들지 않는다 — 하나의 Producer가 모든 파티션을 담당한다.");
            System.out.println("     (EOSMode.V1 시절에는 입력 파티션마다 별도 transactional.id가 필요했고,");
            System.out.println("      그래서 파티션 수만큼 Producer가 늘어나는 확장성 문제가 있었다.)");
            printSeparator();

            assertThat(mine)
                    .as("Spring이 만든 트랜잭션이 실제로 브로커 코디네이터에 등록되어 있어야 한다")
                    .isNotEmpty();
            assertThat(mine)
                    .as("전체 id의 접미사 형태는 구현 세부사항이므로 prefix로 시작한다는 것만 단정한다")
                    .allSatisfy(listing ->
                            assertThat(listing.transactionalId()).startsWith(Q2_TX_PREFIX));

            // 등록된 id로 개별 조회도 되는지 확인한다 — Lab01에서 손으로 준 txId를 조회한 것과 같은 경로다.
            String actualId = mine.get(0).transactionalId();
            assertThat(describeTransaction(actualId))
                    .as("Lab01에서 직접 지정한 transactional.id와 조회 방법이 다르지 않다")
                    .isNotNull();

            List<ConsumerRecord<String, String>> consumed =
                    readCommitted(Q2_TOPIC, "lab05-q2-" + System.nanoTime(), 1, 5000);
            assertThat(consumed)
                    .as("커밋된 트랜잭션이므로 메시지도 정상적으로 보인다")
                    .hasSize(1);
        } finally {
            factory.destroy();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Q3. KafkaTransactionManager + TransactionTemplate 경로에서도 전부-또는-전무다")
    void transactionTemplateRollsBackAllSendsTogether() {
        DefaultKafkaProducerFactory<String, String> factory = producerFactory(Q3_TX_PREFIX);
        try {
            KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);

            // @Transactional이 붙은 메서드가 내부적으로 타는 경로를 손으로 재현한 것이다.
            // AOP 프록시가 하는 일이 결국 이 TransactionTemplate.execute(...)와 같다.
            // KafkaTransactionManager가 begin/commit/rollback을 담당하고,
            // KafkaTemplate.send()는 TransactionSynchronizationManager에 묶인 Producer를 찾아 그 위에서 쓴다.
            KafkaTransactionManager<String, String> txManager = new KafkaTransactionManager<>(factory);
            TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

            // ── 롤백 경로 ──────────────────────────────────────────────
            // 3건을 보낸 뒤 마지막에 예외를 던진다. 롤백 단위는 send 하나하나가 아니라 트랜잭션 전체다.
            TransactionCallback<Void> failing = (TransactionStatus status) -> {
                template.send(Q3_ROLLBACK, "step-1", "1단계 완료");
                template.send(Q3_ROLLBACK, "step-2", "2단계 완료");
                template.send(Q3_ROLLBACK, "step-3", "3단계 완료");
                template.flush(); // 브로커 로그에는 이미 기록된 상태로 만든다
                throw new RuntimeException("4단계에서 실패");
            };

            assertThatThrownBy(() -> transactionTemplate.execute(failing))
                    .as("런타임 예외가 나면 KafkaTransactionManager가 rollback 후 예외를 전파한다")
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("4단계에서 실패");

            List<ConsumerRecord<String, String>> rolledBack =
                    readCommitted(Q3_ROLLBACK, "lab05-q3-rb-c-" + System.nanoTime(), 99, 4000);
            List<ConsumerRecord<String, String>> rolledBackRaw =
                    readUncommitted(Q3_ROLLBACK, "lab05-q3-rb-u-" + System.nanoTime(), 3, 5000);

            printRecords("롤백 후 read_committed", rolledBack);
            printRecords("롤백 후 read_uncommitted", rolledBackRaw);

            assertThat(rolledBack)
                    .as("3건 중 앞의 3건만 살아남는 일은 없다 — 전부 롤백된다")
                    .isEmpty();
            assertThat(rolledBackRaw)
                    .as("Lab02와 동일하게, 롤백된 메시지도 로그에는 남아 있고 소비자가 걸러낸다")
                    .hasSize(3);

            // ── 커밋 경로 ──────────────────────────────────────────────
            // 예외 없이 끝나면 같은 3건이 전부 확정된다. 추상화 계층이 바뀌어도 결과는 Lab03과 같다.
            TransactionCallback<Void> succeeding = (TransactionStatus status) -> {
                template.send(Q3_COMMIT, "step-1", "1단계 완료");
                template.send(Q3_COMMIT, "step-2", "2단계 완료");
                template.send(Q3_COMMIT, "step-3", "3단계 완료");
                return null;
            };
            transactionTemplate.execute(succeeding);

            List<ConsumerRecord<String, String>> committed =
                    readCommitted(Q3_COMMIT, "lab05-q3-ok-" + System.nanoTime(), 3, 5000);

            printRecords("정상 종료 후 read_committed", committed);
            System.out.printf("  커밋 토픽 LSO=%d, HW=%d — 메시지 3건 + 커밋 마커 1건%n",
                    lastStableOffset(Q3_COMMIT, 0), highWatermark(Q3_COMMIT, 0));
            printSeparator();

            assertThat(committed)
                    .as("예외 없이 끝나면 3건이 한 번에 확정된다")
                    .hasSize(3);
            assertThat(committed).extracting(ConsumerRecord::value)
                    .containsExactlyInAnyOrder("1단계 완료", "2단계 완료", "3단계 완료");
            assertThat(highWatermark(Q3_COMMIT, 0))
                    .as("커밋 마커가 오프셋 1칸을 차지하는 것도 Lab02 Q3와 동일하다")
                    .isEqualTo(4);
        } finally {
            factory.destroy();
        }
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /**
     * 트랜잭션이 활성화된 ProducerFactory를 만든다.
     * setTransactionIdPrefix()를 호출해야 transactionCapable()이 true가 되고,
     * 그래야 KafkaTemplate이 executeInTransaction / 트랜잭션 send를 허용한다.
     * (raw Producer 실습에서 ProducerConfig.TRANSACTIONAL_ID_CONFIG를 직접 넣던 자리다.)
     */
    private static DefaultKafkaProducerFactory<String, String> producerFactory(String txIdPrefix) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // transactional.id가 붙으면 enable.idempotence=true, acks=all이 강제된다. 명시적으로도 켜둔다.
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configs.put(ProducerConfig.ACKS_CONFIG, "all");

        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(configs);
        factory.setTransactionIdPrefix(txIdPrefix);
        return factory;
    }

    /**
     * prefix로 시작하는 트랜잭션이 코디네이터에 나타날 때까지 잠깐 기다렸다가 반환한다.
     * 커밋 직후 곧바로 조회하면 아직 전파 전일 수 있어 짧게 재시도한다.
     */
    private static List<TransactionListing> findTransactionsWithPrefix(String prefix) throws Exception {
        List<TransactionListing> found = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            found = listTransactions().stream()
                    .filter(listing -> listing.transactionalId().startsWith(prefix))
                    .toList();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        return found;
    }
}
