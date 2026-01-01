# 05. 로컬 실행: docker-compose(KRaft)

이 프로젝트는 ZooKeeper 없이 **KRaft 모드**로 Kafka를 띄운다. 설정은 `docker-compose.yml`에 있다.

## 빠른 시작
```bash
docker compose up -d
docker compose ps
```

브로커가 뜨면 앱은 `localhost:9092`로 접속한다. (Spring 설정: `src/main/resources/application.yaml`)

## KRaft 모드에서 자주 보는 환경변수
`docker-compose.yml`에서 핵심만 의미를 잡아두면 도움이 된다.

- `KAFKA_PROCESS_ROLES=broker,controller`
  - 한 프로세스가 broker + controller 역할을 같이 한다(학습/로컬용으로 단순).
- `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093`
  - 컨트롤러 쿼럼(투표자) 정의. 단일 노드라 `1@...` 형태.
- `KAFKA_LISTENERS` / `KAFKA_ADVERTISED_LISTENERS`
  - listeners: 브로커가 실제로 열어두는 포트/프로토콜
  - advertised: 클라이언트에게 “나 여기로 접속해”라고 알려주는 주소
  - 로컬 개발에서는 보통 `advertised`를 `localhost:9092`로 둔다.

## 토픽 확인/생성(선택)
Kafka 컨테이너에 들어가 CLI로 토픽을 볼 수 있다. (이미지는 버전에 따라 경로가 다를 수 있음)

1) 컨테이너 이름 확인:
```bash
docker compose ps
```

2) 토픽 목록(예시 경로: `/opt/kafka/bin`):
```bash
docker exec -it <컨테이너명> /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

3) 토픽 생성(자동 생성이 켜져 있지만, 연습용으로 직접 생성해보기):
```bash
docker exec -it <컨테이너명> /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic study-topic --partitions 1 --replication-factor 1
```

## 종료/정리
```bash
docker compose down -v
```

