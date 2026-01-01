# Kafka docker-compose 기동 실패

## 증상
- `docker compose up -d` 실행 시 Kafka 이미지 풀링 실패
- 에러: `docker.io/bitnami/kafka:* not found`

## 원인
- 사용한 `bitnami/kafka` 이미지 태그가 Docker Hub에 존재하지 않음

## 해결
- 이미지 교체: `apache/kafka:3.7.0`
- KRaft 모드 환경변수로 재구성하여 ZooKeeper 요구 제거

## 확인 결과
- 브로커 정상 기동 (KRaft)
- 메시지 전송/수신 성공
  - 요청: `POST /api/messages`
  - 로그: `Consumed message: docker compose test`
