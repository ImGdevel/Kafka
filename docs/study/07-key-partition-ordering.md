# 07. 키 기반 파티셔닝과 순서 보장

이번 단계 목표는 2가지다.
1) **왜 key가 중요한지** 이해하기  
2) **“순서 보장”이 정확히 어디까지인지(파티션 단위)** 체감하기

## 1) Kafka에서 “순서”는 어디까지 보장되나?
Kafka는 **파티션 내부**에서만 순서를 보장한다.

- 같은 토픽이라도 파티션이 여러 개면, 전체 메시지를 “한 줄로” 정렬된 순서로 읽는 것은 보장되지 않는다.
- 따라서 “A 다음에 B가 와야 한다” 같은 요구가 있다면, **A와 B가 같은 파티션**으로 들어가도록 설계해야 한다.

## 2) key를 주면 무슨 일이 생기나?
Producer가 레코드에 **key**를 넣으면(기본 파티셔너 기준):
- 같은 key는 **대개 같은 파티션**으로 라우팅된다.
- 결과적으로 “같은 key(예: 같은 사용자/주문)”에 대해서는 **처리 순서**를 지키기 쉬워진다.

반대로 key가 없으면:
- 레코드가 파티션들로 분산되는데(전략은 클라이언트/버전/설정에 따라 다를 수 있음),
- 순서가 필요한 단위를 같은 파티션으로 묶기 어렵다.

## 3) 이 프로젝트에서 실습: key를 붙여 보내보기

### 3-1) Kafka 기동 + 앱 실행
```bash
docker compose up -d
./gradlew bootRun
```

### 3-2) key 없이 보내기(기본)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"no key 1"}'
```

### 3-3) 같은 key로 연속 보내기(순서/파티션 확인)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"key":"user-1","message":"m1"}'

curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"key":"user-1","message":"m2"}'
```

앱 로그에서 아래 정보를 확인한다.
- `key=user-1`인 두 메시지가 **같은 partition**으로 들어갔는지
- offset이 증가하면서(`... offset=10`, `... offset=11` 처럼) **순서가 유지**되는지

## 4) 다음 질문(아주 중요)
- “같은 key면 무조건 같은 파티션인가?” → 대부분 그렇지만, 파티션 수 변경/파티셔너 변경/토픽/클라이언트에 따라 달라질 수 있다.
- “그럼 Exactly-once는?” → 순서와는 별개로, 재시도/커밋/트랜잭션까지 포함한 설계가 필요하다.

