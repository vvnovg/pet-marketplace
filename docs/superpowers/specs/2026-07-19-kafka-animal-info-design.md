# Kafka Animal-Info Request/Reply Integration — Design

**Date:** 2026-07-19
**Status:** Approved (pending implementation plan)
**Scope:** A new Kafka-based request/reply integration that lets an external system ask the pet marketplace for information about an animal (listing) by ID and receive a correlated reply.

## 1. Goal & scope

The marketplace receives a request from a Kafka topic carrying a listing ID, looks up the listing, and publishes a reply with animal information to a fixed reply topic, correlated back to the request via a correlation ID.

**In scope:**
- Replier side only (the app *consumes* requests and *produces* replies). No in-app requester is built here.
- A dedicated, stable reply contract (`AnimalInfoResponse`) independent of the REST `ListingResponse` DTO.
- External configuration of broker, consumer/producer settings, and topic names.
- Integration tests running in both existing test modes (Testcontainers embedded, and stand).

**Out of scope:**
- Per-message authentication/authorization (broker is the trust boundary).
- An in-app Kafka requester (the `ReplyingKafkaTemplate` path is left open by header naming).
- Auto-creation of topics by the app at runtime.
- Producing/listening for any other business events beyond this single animal-info flow.

## 2. Decisions

| Decision | Choice |
|---|---|
| Request identifies the animal by | Listing ID (UUID) |
| Request/reply correlation | Correlation ID in a Kafka header, copied to the reply |
| Header name for correlation | `KafkaHeaders.CORRELATION_ID` (Spring Kafka standard, leaves `ReplyingKafkaTemplate` door open) |
| Reply payload shape | Dedicated `AnimalInfoResponse` DTO (not the REST `ListingResponse`) |
| Error handling | Always reply on the reply topic with a `status` discriminator (`OK` / `NOT_FOUND` / `ERROR`) |
| Security on consumer | Broker is the trust boundary; no per-message auth |
| Visible listing statuses | `ACTIVE`, `RESERVED`, `SOLD` only; non-public statuses map to `NOT_FOUND` |
| Implementation | Manual `@KafkaListener` + `KafkaTemplate` (Approach 1) |
| Configuration | All Kafka wiring externalized to `kafka.*` properties |

## 3. Architecture & components

A new `infrastructure/kafka` package holds the integration, isolated from the REST application layer, consistent with the codebase's cross-cutting-convention. No new domain entity is introduced — "animal info" is a projection over the existing `Listing` aggregate.

All components live under `com.petmarketplace.infrastructure.kafka`:

- **`AnimalInfoRequest`** (record) — request payload: `{ "listingId": "<uuid>" }`.
- **`AnimalInfoResponse`** (record) — reply payload (the dedicated DTO). Fields: `status` (`OK`/`NOT_FOUND`/`ERROR`), `correlationId` (mirrored from the header for logging/debug), `listingId`, `errorMessage` (nullable), plus the animal fields: `title`, `categoryName`, `breedName`, `price`, `currency`, `gender`, `ageMonths`, `color`, `weightKg`, `healthInfo`, `hasVaccination`, `hasDocuments`, `locationCountry`, `locationCity`, `listingStatus`. Animal fields are `null` when `status != OK`.
- **`AnimalInfoRequestListener`** — the `@KafkaListener`. Deserializes the request, reads the `correlationId` header, delegates to `AnimalInfoService`, builds the reply, sends it to the reply topic with the copied correlation header. Catches all processing errors locally and replies with `status=ERROR` — it never rethrows to the container (so the container never retries/redelivers).
- **`AnimalInfoService`** — orchestrates: load the listing by ID via the existing `ListingRepository`, enforce the public-status visibility rule (`ACTIVE`/`RESERVED`/`SOLD` only; otherwise `NOT_FOUND`), map to `AnimalInfoResponse` via `AnimalInfoMapper`.
- **`AnimalInfoMapper`** — MapStruct mapper (Spring component model, consistent with `-Amapstruct.defaultComponentModel=spring`). Maps `Listing` → `AnimalInfoResponse` animal fields and resolves `categoryName`/`breedName` from the listing's category/breed relations.
- **`KafkaConfig`** — `@Configuration` that builds the consumer factory, listener container factory, `KafkaTemplate`/producer factory, and JSON serializer/deserializer beans **from `kafka.*` properties** (no hardcoded broker, topic, or group).
- **`KafkaTopics`** — a `@ConfigurationProperties(prefix = "kafka.topics")` record (`request`, `reply`) injected into the listener and producer so topic names are externalized.

**New Gradle dependencies:**
- `implementation("org.springframework.kafka:spring-kafka")` (version managed by the Boot BOM).
- `testImplementation("org.springframework.kafka:spring-kafka-test")`
- `testImplementation("org.testcontainers:kafka")` (covered by the existing Testcontainers BOM)

## 4. Data flow

```
[External system]
   │ produces { listingId } + header correlationId
   ▼
request topic: pet-marketplace.animal-info.requests   (kafka.topics.request)
   │ @KafkaListener (AnimalInfoRequestListener) consumes
   ▼
AnimalInfoService.findById(listingId)
   │ ListingRepository.findById → Listing
   │ visibility check: status ∈ {ACTIVE, RESERVED, SOLD}? else NOT_FOUND
   │ AnimalInfoMapper.toResponse(listing)
   ▼
AnimalInfoResponse { status, ...animal fields | errorMessage }
   │ KafkaTemplate.send(replyTopic) with correlationId header copied
   ▼
reply topic: pet-marketplace.animal-info.replies   (kafka.topics.reply)
   │ external system consumes, matches correlationId
```

- The listener is single-threaded by default (`kafka.consumer.concurrency` tunable).
- The operation is a pure read; redelivery produces the same reply, so idempotency is not a concern.

## 5. Configuration

All Kafka integration settings are externalized under the `kafka.*` namespace (bound via `@ConfigurationProperties`), overridable per environment via the corresponding env var or profile override — the same philosophy as `security.jwt.*` and `storage.*`.

```yaml
kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  consumer:
    group-id: ${KAFKA_CONSUMER_GROUP:pet-marketplace-animal-info}
    auto-offset-reset: ${KAFKA_AUTO_OFFSET_RESET:earliest}
    concurrency: ${KAFKA_CONCURRENCY:1}
  producer:
    acks: ${KAFKA_PRODUCER_ACKS:all}
  topics:
    request: ${KAFKA_TOPIC_REQUEST:pet-marketplace.animal-info.requests}
    reply:   ${KAFKA_TOPIC_REPLY:pet-marketplace.animal-info.replies}
```

- `KafkaTopics` is a `@ConfigurationProperties(prefix = "kafka.topics")` record, injected where topic names are needed.
- `KafkaConfig` reads the consumer/producer settings from `kafka.*` (not `spring.kafka.*`), keeping Boot's `KafkaProperties` free for the app's own future Kafka use.
- JSON serializer/deserializer beans configured to trust `com.petmarketplace.*` packages.

**Profiles:**
- `application-dev.yml`: `kafka.bootstrap-servers: localhost:9092`, default topics/group.
- `application-test.yml` (embedded, `gradle test`): bound at runtime via `@DynamicPropertySource` to a Testcontainers `KafkaContainer` (see §7).
- `application-stand.yml` (stand, `gradle testOnStand`): `kafka.*` from `STAND_KAFKA_BOOTSTRAP_SERVERS` / `STAND_KAFKA_TOPIC_*` / `STAND_KAFKA_CONSUMER_GROUP` env vars; no Testcontainers.

## 6. Message contract & error handling

### Request — topic `kafka.topics.request`

Payload (JSON):
```json
{ "listingId": "550e8400-e29b-41d4-a716-446655440000" }
```

Header: `correlationId` (string UUID), copied verbatim to the reply. Standard Spring header name `KafkaHeaders.CORRELATION_ID` is used so a future in-app requester built on `ReplyingKafkaTemplate` interoperates without header translation.

### Reply — topic `kafka.topics.reply`

One payload shape for all outcomes. `status` is the discriminator; animal fields are `null` when `status != OK`.

**OK:**
```json
{
  "status": "OK",
  "correlationId": "550e8400-...",
  "listingId": "550e8400-...",
  "title": "British Shorthair kitten",
  "categoryName": "Cats",
  "breedName": "British Shorthair",
  "price": 25000.00,
  "currency": "RUB",
  "gender": "MALE",
  "ageMonths": 3,
  "color": "Blue",
  "weightKg": 1.2,
  "healthInfo": "Vaccinated, vet-checked",
  "hasVaccination": true,
  "hasDocuments": true,
  "locationCountry": "Russia",
  "locationCity": "Moscow",
  "listingStatus": "ACTIVE"
}
```

**NOT_FOUND:**
```json
{ "status": "NOT_FOUND", "correlationId": "...", "listingId": "...", "errorMessage": null }
```

**ERROR:**
```json
{ "status": "ERROR", "correlationId": "...", "listingId": "...", "errorMessage": "Failed to deserialize request" }
```

The `correlationId` header is always copied from the request, even on errors. If a request is so malformed it lacks a correlation header, a fresh UUID is generated and logged at `WARN` so a reply is never dropped for lack of a correlation ID.

### Error matrix

| Situation | `status` | Source |
|---|---|---|
| Listing found and publicly visible (`ACTIVE`/`RESERVED`/`SOLD`) | `OK` | `ListingRepository.findById` + `AnimalInfoMapper` |
| Listing not found **or** found but non-public (`DRAFT`/`PENDING_MODERATION`/`ARCHIVED`/`REJECTED`) | `NOT_FOUND` | single branch — non-public deliberately indistinguishable from "does not exist" to avoid existence-leakage |
| Request cannot be deserialized (missing/invalid `listingId`, malformed JSON) | `ERROR` | caught in the listener before the service is called |
| Unhandled exception during lookup/mapping | `ERROR` | caught in the listener, logged at `ERROR` with correlation context; `errorMessage` is a generic human-readable cause, never internal stack details |

### Listener behavior

- Never rethrows to the container — request/reply demands a definitive answer, and retrying would only duplicate the reply. All error handling is local and ends in a reply.
- Container ACK mode: `RECORD` — offset committed after each record regardless of OK/error; redelivery is never wanted.
- Logging: `INFO` on `OK`, `WARN` on `NOT_FOUND`, `ERROR` on `ERROR`, always with `correlationId`/`listingId` in the log context.

## 7. Infra & testing

### Docker Compose

Add a KRaft-mode single-node Kafka service to `docker-compose.yml` (no Zookeeper) so `docker-compose up -d` brings the broker up alongside Postgres/Redis/Mailpit. Ports: `9092` for the app, plus a host-accessible listener. A healthcheck is wired into compose.

### Topic provisioning

The app does **not** auto-create topics (production brokers commonly have `auto.create.topics.enable=false`). Integration tests provision topics explicitly via `AdminClient` (or `NewTopic` beans in a test config) at start. This is documented so operators know to create the two topics in environments where the broker doesn't auto-create.

### Testing

Two tests, both extending `IntegrationTestBase` so they run in both embedded (Testcontainers) and stand modes alongside the existing suite:

1. **`AnimalInfoKafkaIntegrationTest`** — the request/reply contract, driven manually with a `KafkaTemplate` + reply-topic poll loop (no `@KafkaListener` in tests, for determinism):
   - `ACTIVE` seeded listing → `OK` with correct animal fields.
   - `RESERVED` and `SOLD` listings → `OK`.
   - Non-existent listing ID → `NOT_FOUND`.
   - ID of a `DRAFT` listing → `NOT_FOUND` (visibility rule).
   - Malformed JSON / missing `listingId` → `ERROR` with `correlationId` echoed.
   - Each case asserts the reply's `correlationId` header matches the request.

2. **`AnimalInfoServiceTest`** — a focused unit test for the visibility rule and mapping, mocking `ListingRepository`, no Kafka. Fast; runs as part of `gradle test`.

### Testcontainers wiring (embedded mode)

`IntegrationTestBase`'s static block starts a `KafkaContainer` once per JVM as a sibling to the existing shared Postgres/Redis containers (same "not `@Container`, to survive the cached Spring context" pattern documented in CLAUDE.md). `@DynamicPropertySource` binds `kafka.bootstrap-servers` to the container.

## 8. Open / future

- **`ReplyingKafkaTemplate` requester:** header naming leaves this open; a future in-app requester can be added without changing the replier contract.
- **SASL/SSL to broker:** a config knob to enable broker auth later, not built now (broker is the trust boundary).
- **DLQ for processing failures:** not added; `ERROR` replies on the reply topic cover caller visibility. Can be layered on if ops needs a separate technical-failure sink.