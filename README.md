# Dual Write Migration Scenario

Kotlin + Spring MVC + JPA 기반으로 서비스 무중단 데이터베이스 마이그레이션 시나리오를 재현하는 프로젝트다. 구형 클러스터는 `old-primary` 와 `old-secondary` 로 구성되고, 신규 데이터베이스는 `new-db` 다. 기능 플래그는 OpenFeature + `flagd` 로 관리하고, `OLD -> DUAL_WRITE -> NEW` 전환 과정에서 데이터가 어떻게 기록되는지 실제로 검증할 수 있다.

## 목표

- 구형 DB에만 기록되던 서비스가 중간에 `DUAL_WRITE` 로 전환될 때 동일 ID로 신규 DB에도 복제되는지 확인
- 최종적으로 `NEW` 로 전환되면 신규 DB에만 이어서 기록되는지 확인
- 연관 엔티티가 있는 쓰기 작업, 특히 `Comment -> Post -> Member/Board` 경로가 신규 DB에 같은 ID로 재현되는지 확인
- `old-primary -> old-secondary` 는 MySQL primary-replica 로 구성해 읽기 경로를 분리

## 아키텍처

- 애플리케이션: Kotlin, Spring Boot 4, Spring MVC, Spring Data JPA, Micrometer
- 플래그: OpenFeature Java SDK + `flagd`
- 구형 클러스터:
  - `old-primary`: write source of truth
  - `old-secondary`: MySQL replica, read path
- 신규 DB:
  - `new-db`: `DUAL_WRITE` 에서 비동기 복제 대상, `NEW` 에서 최종 write/read 대상

## 기록 방식

- `OLD`
  - write: `old-primary`
  - read: `old-secondary`
- `DUAL_WRITE`
  - write: `old-primary`
  - `AFTER_COMMIT` 이벤트 기반으로 `new-db` 에 비동기 복제
  - `old-secondary` 는 primary-replica 로 동일 데이터 반영
- `NEW`
  - write/read: `new-db`

## Hibernate 레벨 핵심 동작

이 시나리오가 Hibernate/JPA 기반으로 성립하는 핵심은 다음과 같다.

- 영속성 컨텍스트와 dirty checking
  - 서비스는 엔티티를 조회하고 값만 변경한다.
  - Hibernate가 변경을 추적하고 커밋 시 SQL을 생성한다.
- 트랜잭션 readOnly 기반 라우팅
  - `AbstractRoutingDataSource` 와 Spring 트랜잭션의 readOnly 상태를 이용해 read/write 경로를 분리한다.
  - `NEW` 모드에서는 AOP로 라우팅 키를 바꿔 `new-db` 로 보낸다.
- `AFTER_COMMIT` 트랜잭션 이벤트
  - `DUAL_WRITE` 에서 원본인 `old-primary` 커밋이 끝난 뒤에만 신규 DB 복제를 수행한다.
- Hibernate 엔티티 스냅샷화
  - 관리 중인 엔티티 상태를 `MemberSnapshot`, `BoardSnapshot`, `PostSnapshot`, `CommentSnapshot` 으로 변환해 복제 payload로 사용한다.
- 부모 우선 복제
  - `Comment -> Post -> Member/Board` 같은 관계 때문에 신규 DB에는 부모 엔티티를 먼저 upsert 한 뒤 자식을 기록한다.
- ID 유지
  - 신규 DB가 새 ID를 생성하지 않고, old primary에서 생성된 ID를 그대로 사용해 old/new 간 정합성을 맞춘다.
- 복제는 Hibernate 자동 merge가 아닌 native upsert 사용
  - 신규 DB 복제는 `insert ... on duplicate key update` 로 처리해 detached/merge 충돌을 줄이고, 명시적으로 same ID replication을 보장한다.

즉, 비즈니스 로직은 JPA 스타일 그대로 유지하고, Hibernate가 관리하는 엔티티 상태를 커밋 이후 snapshot으로 꺼내 신규 DB에 같은 ID로 재기록하는 방식이 이 프로젝트의 핵심이다.

핵심 구현 포인트:
- [DataSourceRoutingConfig.kt](src/main/kotlin/com/example/demo/config/DataSourceRoutingConfig.kt)
  - `AbstractRoutingDataSource` 로 read/write 및 NEW 전환 라우팅 처리
- [MigrationAspects.kt](src/main/kotlin/com/example/demo/service/MigrationAspects.kt)
  - 비즈니스 서비스 코드에 마이그레이션 분기를 넣지 않고 AOP 로 라우팅/복제 이벤트 발행
- [MigrationReplication.kt](src/main/kotlin/com/example/demo/service/MigrationReplication.kt)
  - `AFTER_COMMIT` 비동기 복제와 연관 엔티티 부모 우선 upsert 처리

## 스키마와 샘플 데이터

테이블:
- `members`
- `boards`
- `posts`
- `post_comments`

컨테이너 초기화 시 미리 seed 된다:
- `members`: 600건
- `boards`: 600건
- `posts`: 1200건
- `post_comments`: 2400건

seed는 애플리케이션 bootstrap 이 아니라 DB init 단계에서 생성된다.
- [mysql/primary/init/02-schema-and-seed.sql](mysql/primary/init/02-schema-and-seed.sql)
- [mysql/new/init/01-schema-and-seed.sql](mysql/new/init/01-schema-and-seed.sql)

복제 관련 파일:
- [mysql/primary/init/01-create-replication-user.sql](mysql/primary/init/01-create-replication-user.sql)
- [mysql/secondary/start-replica.sh](mysql/secondary/start-replica.sh)

## 실행

Docker Compose 기반:

```bash
make up
make app
```

수동 실행:

```bash
docker compose -f compose.yaml up -d
./gradlew bootRun
```

기본 확인:
- API: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`
- 현재 모드: `GET /api/migration/mode`

## 재현 자동화

### 최종 시나리오

`OLD -> DUAL_WRITE -> NEW` 순으로 플래그를 바꾸며 comment 3건씩 생성하는 최종 시나리오 스크립트:

```bash
make final-scenario
```

실체는 [run_final_scenario.sh](scripts/run_final_scenario.sh) 이다.

### 자주 쓰는 명령

```bash
make up
make down
make restart
make app
make flag-old
make flag-dual
make flag-new
make reset-new-db
make verify-seed
```

`make reset-new-db` 는 신규 DB만 비우고 시퀀스를 seed 기준으로 되돌린다.

## Docker Compose 동작

이 프로젝트는 `bootRun` 시 Spring Boot Docker Compose 지원을 기본 활성화한다.

- 애플리케이션 시작 시 `docker compose up`
- 애플리케이션 종료 시 `docker compose down`

공식 Spring Boot 문서 기준으로 Docker Compose 지원은 개발 시 애플리케이션 시작 때 `docker compose up`, 종료 시 `docker compose stop` 을 수행하며, `spring.docker.compose.lifecycle-management` 와 `spring.docker.compose.stop.command` 로 이를 제어할 수 있다. 이 프로젝트는 `start-and-stop` 과 `down` 을 명시적으로 사용한다. 출처: [Spring Boot Development-time Services](https://docs.spring.io/spring-boot/reference/features/dev-services.html)

## 주요 API

- `GET/POST/PUT/DELETE /api/members`
- `GET/POST/PUT/DELETE /api/boards`
- `GET/POST/PUT/DELETE /api/posts`
- `GET/POST/PUT/DELETE /api/comments`

예시:

```bash
curl -X POST http://localhost:8080/api/comments \
  -H 'Content-Type: application/json' \
  -d '{"body":"comment-replication-check","authorId":42,"postId":42}'
```

## 관찰 포인트

메트릭:
- `datasource.old-primary.connections.*`
- `datasource.old-secondary.connections.*`
- `datasource.new.connections.*`
- `async.executor.*`
- `migration.mode.evaluations`
- `migration.replication.events`

상세 모니터링 가이드는 [MONITORING.md](MONITORING.md) 를 참고한다.

`DUAL_WRITE` 검증 시에는:
- `old-primary` write 증가
- `new-db` replication write 증가
- `migration.replication.events{result="success"}` 증가

## 현재 검증된 시나리오

- `OLD` 에서는 old cluster 에만 기록
- `DUAL_WRITE` 에서는 old/new 에 동일 ID로 기록
- `NEW` 에서는 신규 DB에만 이어서 기록
- `Comment` 생성 시 신규 DB에 `Comment`, `Post`, `Member`, `Board` 연관 엔티티가 함께 복제
- `old-primary -> old-secondary` 는 실제 MySQL replica 로 동작

## 주의사항

- `new-db` 에 대한 out-of-band 직접 쓰기는 cutover 이전에 피하는 것이 맞다
- 이 프로젝트는 “보수적인 스키마 변경 최소화”를 우선으로 둔 시뮬레이터다
- ID 및 cutover 전략에 대한 추가 고려사항은 이후 별도 운영 문서로 정리하는 것이 적절하다

## AUTO_INCREMENT 운영 전략

스키마 변경을 최소화해야 하므로, 운영 대응은 테이블 구조 변경보다 쓰기 규율과 점검 절차 위주로 가져가는 것이 안전하다.

- cutover 전까지 `new-db` 에 대한 직접 쓰기를 금지한다.
  - `DUAL_WRITE` 동안 신규 DB는 복제 대상이어야 하며, 애플리케이션 복제 경로 외의 수동 insert/update/delete 는 금지하는 것이 맞다.
- ID 생성 주체는 cutover 전까지 `old-primary` 하나로 유지한다.
  - `DUAL_WRITE` 에서는 old primary 가 생성한 ID 를 `new-db` 에 그대로 반영한다.
- cutover 직전 테이블별 `AUTO_INCREMENT` 값을 점검한다.
  - `members`: `max(id) + 1`
  - `boards`: `max(id) + 1`
  - `posts`: `max(id) + 1`
  - `post_comments`: `max(id) + 1`
- 필요 시 cutover 전에만 보정한다.
  - 예: `ALTER TABLE post_comments AUTO_INCREMENT = <old-primary max(id)+1>`
- 운영 중 예기치 않은 직접 쓰기나 점프가 발생하면 우선 원인을 식별하고, cutover 전에 시퀀스를 다시 맞춘다.
- 가능하면 신규 DB에 대해 복제 전용 계정과 일반 접근 계정을 분리해 사람이나 배치의 우발적 write 를 막는다.

핵심 원칙:
- cutover 전: old primary 만 ID 생성
- `DUAL_WRITE`: same ID replication
- cutover 직전: `AUTO_INCREMENT` 정합성 점검
- cutover 후: new DB 가 단독 ID 생성 주체가 됨

## 복제 일반화에 대한 판단

현재 [MigrationReplication.kt](src/main/kotlin/com/example/demo/service/MigrationReplication.kt) 는 `Member`, `Board`, `Post`, `PostComment` 를 엔티티별 메서드로 복제한다. 부모-자식 관계 때문에 부모를 먼저 upsert 해야 하고, 이 프로젝트는 FK 제약 없이도 관계 정합성을 유지해야 하므로 현재는 native query 기반으로 명시적으로 처리한다.

왜 완전 일반화를 바로 하지 않았는가:

- 부모-자식 저장 순서를 자동 계산해야 한다.
  - 예: `Comment -> Post -> Member/Board`
- 엔티티마다 upsert SQL 이 다르다.
  - 테이블명, PK 컬럼, enum/datetime 컬럼, FK 컬럼 구성이 다르다.
- delete 는 upsert 와 반대로 자식 우선 처리여야 한다.
- JPA metamodel 기반 완전 자동화는 가능하지만, reflection/naming strategy/converter/collection 연관까지 들어가면 구현과 디버깅 복잡도가 높다.

현재 프로젝트의 판단:

- 완전 자동화보다는 “선언형 반자동 일반화”가 더 적절하다.
- 즉, 공통 복제 엔진은 만들되 엔티티별로 최소 설정만 제공하는 방식이 현실적이다.

추천하는 다음 단계:

- `ReplicationPlan<T>` 같은 선언형 메타데이터 도입
  - `entityClass`
  - `tableName`
  - `idProperty`
  - `scalarProperties`
  - `parentRelations`
  - `deleteOrderWeight`
- 공통 `ReplicationWriter`
  - plan을 읽어서 upsert SQL 생성
  - 부모 우선 upsert
  - delete 역순 처리

즉 결론은 다음과 같다.

- 엔티티 수가 적을 때는 현재 방식이 가장 단순하고 명확하다.
- 엔티티 수가 늘어나면 완전 일반화보다 선언형 반자동 일반화가 안전하고 유지보수성이 좋다.
