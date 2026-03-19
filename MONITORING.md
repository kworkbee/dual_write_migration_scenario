# Monitoring Guide

## Why More Metrics

무중단 DB 마이그레이션에서는 단순히 애플리케이션이 살아있는지만 보면 부족하다. 다음 질문에 답할 수 있어야 한다.

- 현재 플래그가 어떤 상태인가
- 쓰기 요청이 어느 DB로 향하고 있는가
- `DUAL_WRITE` 중 신규 DB 복제가 성공하고 있는가
- 엔티티별 실패가 특정 타입에 집중되는가
- old/new 사이에 write throughput 또는 성공률 차이가 있는가
- 비동기 복제 backlog 가 쌓이고 있는가

## Already Implemented

### Connection Pool

- `datasource.old-primary.connections.active`
- `datasource.old-primary.connections.idle`
- `datasource.old-primary.connections.pending`
- `datasource.old-primary.connections.total`
- `datasource.old-secondary.connections.*`
- `datasource.new.connections.*`

용도:
- 특정 타깃 DB의 커넥션 고갈 여부 확인
- `DUAL_WRITE` 구간에서 `old-primary` 와 `new` 양쪽 write pressure 비교

### Async Executor

- `async.executor.pool.size`
- `async.executor.active.count`
- `async.executor.queue.size`
- `async.executor.completed.tasks`

용도:
- 비동기 복제 worker 포화 여부 확인
- queue 증가 시 신규 DB 복제 지연 징후 확인

### Feature Flag

- `migration.mode.evaluations{mode=OLD|DUAL_WRITE|NEW}`
- `migration.mode.current.ordinal`
- `migration.mode.transitions{from=...,to=...}`

용도:
- 어떤 모드가 실제 요청 처리에 사용되는지 확인
- 모드 전환 이력 추적

### Service Write Metrics

- `migration.service.write.operations{mode,entity,operation,target,result}`

예시 태그:
- `mode=OLD|DUAL_WRITE|NEW`
- `entity=member|board|post|comment`
- `operation=create|update|delete`
- `target=old-primary|new-db`
- `result=success|failure`

용도:
- 엔티티별 write 성공/실패율 계산
- 모드별 target write 분포 확인
- `NEW` 전환 후 실제로 new DB 에만 쓰는지 확인

### Replication Metrics

- `migration.replication.published{entity,action,target=new-db}`
- `migration.replication.events{entity,action,target=new-db,result}`

예시 태그:
- `entity=member|board|post|comment`
- `action=UPSERT|DELETE`
- `result=success|failure`

용도:
- `DUAL_WRITE` 때 복제 발행량과 실제 성공량 비교
- 특정 엔티티만 복제 실패가 증가하는지 탐지

## Recommended Dashboard Views

### 1. Mode and Routing

- `migration.mode.current.ordinal`
- `migration.mode.transitions`
- `migration.service.write.operations` by `target`

확인 포인트:
- `OLD` 에서는 `target=old-primary`
- `DUAL_WRITE` 에서는 서비스 write 는 old-primary, replication publish 는 new-db
- `NEW` 에서는 `target=new-db`

### 2. Replication Health

- `migration.replication.published`
- `migration.replication.events{result=success}`
- `migration.replication.events{result=failure}`
- `async.executor.queue.size`

확인 포인트:
- `published` 와 `success` 의 증가량이 장기적으로 일치해야 함
- `failure > 0` 이면 즉시 원인 확인
- queue size 증가가 지속되면 신규 DB 처리 병목 가능성

### 3. Entity-Level Safety

- `migration.service.write.operations{entity=comment,result=failure}`
- `migration.replication.events{entity=comment,result=failure}`
- `migration.replication.events{entity=post,result=failure}`
- `migration.replication.events{entity=member,result=failure}`
- `migration.replication.events{entity=board,result=failure}`

확인 포인트:
- 자식 엔티티인 `comment` 실패 시 부모 엔티티 복제 실패와 함께 보는 것이 좋음

### 4. DB Resource Pressure

- `datasource.old-primary.connections.pending`
- `datasource.old-secondary.connections.pending`
- `datasource.new.connections.pending`
- `datasource.*.connections.active`

확인 포인트:
- `DUAL_WRITE` 전환 직후 `new` pool 이 급상승하는지
- `OLD_SECONDARY` read path가 포화되는지

## Suggested Additional Metrics

아래는 현재 구현에는 없지만 운영 시 고려할 가치가 있는 메트릭이다.

### Replication Latency

- 이름 예시: `migration.replication.latency`
- 태그: `entity`, `action`
- 의미: old commit 이후 new DB 반영 완료까지 걸린 시간

가치:
- 비동기 복제 backlog 를 수치로 직접 측정 가능

### Old/New Divergence Check

- 이름 예시: `migration.data.parity.mismatch`
- 태그: `entity`
- 의미: old/new row count 또는 checksum 불일치 횟수

가치:
- cutover 직전 정합성 확인

### Flag Staleness / Provider Health

- 이름 예시: `migration.flag.provider.state`
- 태그: `state`
- 의미: `flagd` provider 상태 추적

가치:
- 플래그 stale 상태가 잦을 때 운영 경보 가능

### Manual Write Guard

- 이름 예시: `migration.newdb.out_of_band_writes`
- 의미: 복제 경로 외 write 탐지 횟수

가치:
- `AUTO_INCREMENT` 드리프트와 직접 연결되는 운영 사고 탐지

## Practical Alert Rules

- `migration.replication.events{result="failure"} > 0`
- `async.executor.queue.size > 0` 가 일정 시간 이상 지속
- `datasource.new.connections.pending > 0` 가 일정 시간 이상 지속
- `migration.mode.current.ordinal = 2` 인데 `migration.service.write.operations{target="old-primary"}` 증가
- `migration.mode.current.ordinal = 0` 또는 `1` 인데 신규 DB 직접 write 탐지

## Summary

이 프로젝트에서 운영자가 가장 먼저 봐야 할 축은 네 가지다.

- 모드가 무엇인가
- 서비스 write 가 어디로 가는가
- 신규 DB 복제가 성공하는가
- 비동기/커넥션 리소스가 포화되는가

현재 구현은 이 네 축을 바로 볼 수 있는 기본 메트릭을 제공한다. cutover 직전 정합성 확인과 복제 지연을 더 엄밀하게 보려면 latency/parity 계열 메트릭을 추가하는 것이 다음 단계다.
