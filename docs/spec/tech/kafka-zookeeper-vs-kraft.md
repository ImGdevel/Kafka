# ZooKeeper vs KRaft — 역할, 이벤트 영속성, 차이점

## 1. ZooKeeper의 역할 (Kafka 2.x 이전)

ZooKeeper는 Kafka 클러스터의 외부 조율자(Coordinator)로, 아래 네 가지 역할을 담당했다.

### 1.1 클러스터 메타데이터 저장

ZooKeeper는 분산 키-값 저장소를 제공하며, Kafka는 이곳에 클러스터 전체 상태를 기록했다.

| ZooKeeper 경로 | 저장 내용 |
|---|---|
| `/brokers/ids/{id}` | 브로커 생존 여부, 호스트, 포트 |
| `/brokers/topics/{topic}` | 파티션 수, 복제 구성 |
| `/brokers/topics/{topic}/partitions/{p}/state` | 파티션 리더, ISR 목록 |
| `/controller` | 현재 Controller 브로커 ID |
| `/config/topics/{topic}` | 토픽별 설정 오버라이드 |

### 1.2 Controller 브로커 선출

Kafka 클러스터에서 파티션 리더 관리와 ISR 조정을 담당하는 브로커를 **Controller**라고 한다.
ZooKeeper의 ephemeral node 메커니즘으로 Controller를 선출한다.

```
1. 모든 브로커가 기동 시 /controller znode 생성을 시도
2. 먼저 생성에 성공한 브로커 → Controller 승격
3. Controller 브로커 다운 → ZooKeeper가 ephemeral node 자동 삭제
4. 나머지 브로커들이 다시 /controller 생성 경쟁 → 새 Controller 선출
```

### 1.3 브로커 생존 상태 감지

브로커가 기동되면 ZooKeeper에 ephemeral node(`/brokers/ids/{id}`)를 등록한다.
브로커와 ZooKeeper 사이에 세션이 끊기면 (session timeout 초과) ZooKeeper가 해당 node를 자동으로 삭제한다.
Controller는 이 삭제 이벤트를 watch로 감지하여 해당 브로커의 파티션 리더를 재선출한다.

### 1.4 컨슈머 그룹 오프셋 저장 (초기 버전)

초기 Kafka에서는 컨슈머 그룹의 오프셋을 ZooKeeper에 저장했다.
이후 Kafka 내부 토픽 `__consumer_offsets`로 이전되었으며, 현재는 ZooKeeper를 사용하지 않는다.

---

## 2. KRaft의 역할 (Kafka 3.x 이후, 현재 프로젝트)

KRaft(Kafka Raft)는 ZooKeeper 없이 Kafka 자체 Raft 합의 알고리즘으로 클러스터를 관리한다.

### 2.1 메타데이터 내부화

ZooKeeper가 담당하던 클러스터 메타데이터를 Kafka 내부 토픽 `__cluster_metadata`에 이벤트 로그로 저장한다.

```
__cluster_metadata (파티션 1개, 단일 리더):
  offset 0: BrokerRegistration(broker-1, host=kafka-1, port=29092)
  offset 1: BrokerRegistration(broker-2, host=kafka-2, port=29092)
  offset 2: TopicRecord(topic=study-topic, partitions=3, rf=3)
  offset 3: PartitionRecord(topic=study-topic, partition=0, leader=1, isr=[1,2,3])
  ...
```

모든 브로커는 이 토픽의 최신 스냅샷을 메모리에 캐시하므로 메타데이터 조회에 네트워크 왕복이 없다.

### 2.2 컨트롤러 쿼럼 (Controller Quorum)

KRaft에서는 컨트롤러 역할을 하는 노드들이 **쿼럼(Quorum)**을 구성한다.
Raft 알고리즘에 따라 과반수(n/2 + 1) 투표로 리더 컨트롤러를 선출한다.

```
3개 컨트롤러 쿼럼 (현재 구성):
  과반수 = 2표 필요
  → 1개 컨트롤러 장애 시: 나머지 2개로 정상 운영
  → 2개 컨트롤러 장애 시: 쿼럼 구성 불가 → 클러스터 중단
```

> 쿼럼 voter 목록은 `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093`으로 정적으로 지정한다.

### 2.3 운영 모드

| 모드 | 설명 | 현재 구성 |
|---|---|---|
| **combined** | 브로커 + 컨트롤러 역할 겸임 | 현재 프로젝트 (3개 노드 전부) |
| **dedicated** | 컨트롤러 전용 노드 분리 | 프로덕션 대규모 환경 권장 |

현재 프로젝트 구성 (`docker-compose.yml`):

```yaml
KAFKA_PROCESS_ROLES: broker,controller   # combined mode
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
KAFKA_LISTENERS: INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093
```

---

## 3. Kafka 이벤트 저장과 영속성 보장

### 3.1 로그 세그먼트 파일

Kafka는 메시지를 파티션 디렉토리 아래 **로그 세그먼트 파일**에 추가 전용(append-only)으로 기록한다.

```
/kafka-data/
└── study-topic-0/          ← 토픽-파티션 디렉토리
    ├── 00000000000000000000.log    ← 실제 메시지 바이트
    ├── 00000000000000000000.index  ← 오프셋 → 파일 오프셋 매핑
    ├── 00000000000000000000.timeindex  ← 타임스탬프 → 오프셋 매핑
    └── 00000000000000001000.log    ← 다음 세그먼트 (1000번 오프셋부터)
```

- 기존 메시지는 수정되지 않는다 — append-only 구조
- 세그먼트 파일은 `log.segment.bytes` (기본 1GB) 또는 `log.roll.hours` (기본 168h) 초과 시 새 파일로 롤링

### 3.2 복제 (Replication)

모든 파티션에는 하나의 **리더**와 하나 이상의 **팔로워**가 있다.
프로듀서는 리더에게만 쓰고, 팔로워는 리더 로그를 복제(fetch)한다.

```
study-topic 파티션 0:
  리더: broker-1
  팔로워: broker-2, broker-3

프로듀서 → broker-1 (리더) 쓰기
broker-2 → broker-1 fetch
broker-3 → broker-1 fetch
ISR = [1, 2, 3]  (모두 최신 상태 유지)
```

**ISR (In-Sync Replicas)**: 리더와 동기화된 팔로워 목록.
팔로워가 `replica.lag.time.max.ms` 이내에 fetch를 완료하지 못하면 ISR에서 제거된다.

### 3.3 내구성 수준 (acks 설정)

프로듀서의 `acks` 설정이 쓰기 내구성을 결정한다.

| acks | 동작 | 내구성 | 성능 |
|---|---|---|---|
| `0` | 응답 대기 없음 | 최하 (유실 가능) | 최고 |
| `1` | 리더 로컬 저장 확인 | 중간 (리더 장애 시 유실) | 중간 |
| `all` (`-1`) | ISR 전체 저장 확인 | 최고 (ISR 모두 복제 후 응답) | 낮음 |

### 3.4 min.insync.replicas와 acks=all 조합

`acks=all`만으로는 ISR이 1개(리더만)인 경우에도 쓰기가 성공한다.
`min.insync.replicas`를 함께 설정해 최소 ISR 수를 강제한다.

```
현재 프로젝트 설정:
  acks=all (프로듀서)
  min.insync.replicas=2 (브로커/토픽)

→ ISR 2개 이상 확보 시에만 쓰기 성공
→ ISR 1개로 줄어들면 NotEnoughReplicasException
→ 데이터 유실보다 에러 반환을 선택하는 전략
```

### 3.5 보존 정책 (Retention)

메시지는 컨슈머가 읽었다고 삭제되지 않는다. 보존 정책에 따라 삭제된다.

| 정책 | 설정 | 기본값 |
|---|---|---|
| 시간 기반 | `retention.ms` | 7일 (604800000ms) |
| 크기 기반 | `retention.bytes` | 무제한 (-1) |
| 압축 정책 | `log.cleanup.policy` | `delete` (또는 `compact`) |

### 3.6 오프셋 인덱스

`.index` 파일은 희소(sparse) 인덱스로, 특정 오프셋의 메시지를 O(log n) 바이너리 탐색으로 찾는다.
전체 로그를 처음부터 스캔하지 않아도 된다.

```
.index:
  offset 0    → file position 0
  offset 100  → file position 4892
  offset 200  → file position 9784
  ...

오프셋 150 조회:
  index에서 100 ≤ 150 < 200 구간 찾기
  file position 4892부터 순차 스캔 → 오프셋 150 발견
```

---

## 4. ZooKeeper vs KRaft 비교

| 항목 | ZooKeeper 방식 | KRaft 방식 |
|---|---|---|
| **메타데이터 저장** | ZooKeeper 외부 프로세스 (ZNode 트리) | `__cluster_metadata` 토픽 (Kafka 내부) |
| **컨트롤러 선출** | ZooKeeper ephemeral node 경쟁 | Raft 합의 투표 (과반수) |
| **의존성** | Kafka + ZooKeeper 별도 운영 | Kafka 단독 운영 |
| **장애 복구 속도** | 느림 — ZK 상태 → Controller 동기화 필요 | 빠름 — 메타데이터가 이미 복제된 상태 |
| **확장성** | ZooKeeper 병목 (수만 파티션 이후 성능 저하) | 수백만 파티션 지원 목표 |
| **운영 복잡도** | ZooKeeper 클러스터 별도 설치·모니터링 필요 | 단일 시스템 관리 |
| **메타데이터 일관성** | Controller ↔ ZooKeeper 2단계 동기화 | 컨트롤러 쿼럼 내 Raft 로그로 단일 진실 공급원 |
| **지원 버전** | Kafka 3.x에서 deprecated, 4.0에서 제거 예정 | Kafka 2.8+ preview, 3.3+ GA |

### 4.1 메타데이터 업데이트 흐름 비교

**ZooKeeper 방식**:

```
브로커 장애 감지:
  ZooKeeper (세션 타임아웃 감지)
  → Controller (ZK watch 수신)
  → Controller가 ZK에 새 리더 기록
  → Controller가 모든 브로커에 LeaderAndIsrRequest 전송
  → 브로커들이 ZK에서 최신 메타데이터 조회

단점: ZK ↔ Controller ↔ 브로커 간 다단계 동기화 필요
```

**KRaft 방식**:

```
브로커 장애 감지:
  Active Controller (하트비트 타임아웃 감지)
  → Controller가 __cluster_metadata에 변경 이벤트 기록 (Raft 커밋)
  → 모든 브로커가 해당 토픽을 fetch하여 메타데이터 직접 갱신
  → Follower Controller들도 동일 로그 보유

장점: 단일 로그 소스, 동기화 단계 제거
```

---

## 5. 현재 프로젝트 구성

현재 프로젝트는 **KRaft combined mode 3-브로커** 구성이다.

```
kafka-1 (broker + controller, port 9092)
kafka-2 (broker + controller, port 9094)
kafka-3 (broker + controller, port 9095)

Controller Quorum: kafka-1, kafka-2, kafka-3
Active Controller: 3개 중 Raft 투표로 1개 선출
```

관련 문서:

- [Kafka 클러스터링 설계](./kafka-clustering.md) — ISR, 리더 선출, 순서 보장, 검증 명령
