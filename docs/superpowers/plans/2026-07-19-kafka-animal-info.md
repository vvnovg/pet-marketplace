# Kafka Animal-Info Request/Reply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Kafka request/reply integration where the app consumes a `{ listingId }` request from a Kafka topic and publishes an `AnimalInfoResponse` (or an error envelope) to a fixed reply topic, correlated by a `correlationId` header.

**Architecture:** New `infrastructure/kafka` package: `@KafkaListener` + `KafkaTemplate` (Approach 1, manual request/reply). A dedicated `AnimalInfoResponse` DTO carries a `status` discriminator (`OK` / `NOT_FOUND` / `ERROR`). All Kafka wiring is externalized under the `kafka.*` property namespace. JSON serde uses Spring Kafka 4.0's Jackson-3 `JacksonJsonSerializer`/`JacksonJsonDeserializer` (the app is on Jackson 3 — do **not** pull in Jackson 2 serializers).

**Tech Stack:** Spring Boot 4.0.4, Spring Kafka 4.0.x (managed by the Boot BOM), Jackson 3 (`tools.jackson`), MapStruct 1.6.3 (Spring component model), Testcontainers Kafka 1.21.0, Java 26, Gradle 9.

## Global Constraints

- **JDK 26 + Gradle 9.6.1** on PATH; run builds with `gradle` (NOT `./gradlew` — the wrapper targets Gradle 8.14 and cannot run on JDK 26). Set `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` only if `java` is not on PATH.
- **All Gradle test runs** inherit the `api.version=1.45` system property and `TESTCONTAINERS_RYUK_DISABLED=true` env var already configured in `build.gradle.kts`. Do not add separate `@Container` annotations for shared containers — they must live in `IntegrationTestBase`'s static block (the cached Spring context outlives per-class containers; a restarted container gets a new port and the cached DataSource dies).
- **No Jackson 2 on the classpath.** The app uses Jackson 3 (`tools.jackson.databind`). Use `JacksonJsonSerializer` / `JacksonJsonDeserializer` (Spring Kafka 4.0 Jackson-3 classes), never the deprecated Jackson-2 `JsonSerializer`/`JsonDeserializer`. Inject the `tools.jackson.databind.json.JsonMapper` bean.
- **MapStruct** uses the Spring component model (already set via `-Amapstruct.defaultComponentModel=spring`).
- **No Hibernate DDL.** `ddl-auto=none`; schema changes only via Liquibase. This feature adds no entities/tables, so no migration is needed.
- **`@KafkaListener` never rethrows to the container.** All error handling is local and ends in a reply; the container must not retry/redeliver (request/reply demands a definitive answer, and a retry would only duplicate the reply).
- **Visible listing statuses:** `ACTIVE`, `RESERVED`, `SOLD` only. Any other status (or not-found) → `NOT_FOUND`. Non-public statuses are deliberately indistinguishable from "does not exist" (no existence-leakage).
- **Profile-specific YAML** lives in `src/test/resources/`: `application-test.yml` and `application-stand.yml`. The `dev` profile is inline in `src/main/resources/application.yml`. The base (non-profile) section of `application.yml` is loaded in every profile.
- **Commit message style:** conventional commits, e.g. `feat(kafka): ...`. End commit bodies with `Co-Authored-By: Claude <noreply@anthropic.com>`.

**Reference spec:** `docs/superpowers/specs/2026-07-19-kafka-animal-info-design.md`

---

### Task 1: Add Gradle dependencies

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: nothing
- Produces: `org.springframework.kafka:spring-kafka`, `org.springframework.kafka:spring-kafka-test`, and `org.testcontainers:kafka` on the test classpath. The Testcontainers BOM (1.21.0) already imported in `dependencyManagement` manages the `org.testcontainers:kafka` version.

- [ ] **Step 1: Add the dependencies**

In the `dependencies { ... }` block of `build.gradle.kts`, add (place the `spring-kafka` implementation near the other Boot starters, and the test deps near the existing test deps):

```kotlin
    // Kafka integration (animal-info request/reply)
    implementation("org.springframework.kafka:spring-kafka")
```

and in the test dependencies section (after the existing `testImplementation` lines):

```kotlin
    // Kafka test support: embedded broker (Testcontainers) + spring-kafka test utils
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
```

Versions are managed: `spring-kafka` by the Boot 4.0.4 BOM, `org.testcontainers:kafka` by the Testcontainers BOM already declared in `dependencyManagement`.

- [ ] **Step 2: Verify the build resolves and compiles**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL. If resolution fails for `spring-kafka`, confirm the Spring Boot BOM is imported in `dependencyManagement` (`mavenBom(SpringBootPlugin.BOM_COORDS)` — it already is). If `org.testcontainers:kafka` fails to resolve, confirm the Testcontainers BOM line `mavenBom("org.testcontainers:testcontainers-bom:1.21.0")` is present.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build(kafka): add spring-kafka, spring-kafka-test and testcontainers-kafka dependencies"
```

---

### Task 2: Kafka configuration properties and YAML

**Files:**
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/KafkaIntegrationProperties.java`
- Modify: `src/main/resources/application.yml` (base section — add `kafka:` block)
- Modify: `src/test/resources/application-test.yml` (add `kafka:` block)
- Modify: `src/test/resources/application-stand.yml` (add `kafka:` block with `STAND_KAFKA_*` overrides)

**Interfaces:**
- Consumes: nothing
- Produces: `KafkaIntegrationProperties` record (constructor-bound, prefix `kafka`), nested records `Consumer`, `Producer`, `Topics`. Read later by `KafkaConfig` (Task 3) and injected into tests via `@Value("${kafka.bootstrap-servers}")` etc.

- [ ] **Step 1: Create the properties record**

`src/main/java/com/petmarketplace/infrastructure/kafka/KafkaIntegrationProperties.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Kafka integration settings for the animal-info request/reply flow, externalized under the
 * {@code kafka.*} namespace (kept separate from Boot's own {@code spring.kafka.*} so the app's
 * future Kafka use is not pre-empted). Bound via {@code @EnableConfigurationProperties} on
 * {@link KafkaConfig}. Constructor binding on a record — no setters.
 */
@ConfigurationProperties(prefix = "kafka")
public record KafkaIntegrationProperties(
        String bootstrapServers,
        Consumer consumer,
        Producer producer,
        Topics topics
) {

    public record Consumer(String groupId, String autoOffsetReset, Integer concurrency) {
    }

    public record Producer(String acks) {
    }

    public record Topics(String request, String reply) {
    }

    /** Defensive defaults so downstream code can treat absent config as "use the documented default". */
    public String bootstrapServersOrDefault() {
        return bootstrapServers != null ? bootstrapServers : "localhost:9092";
    }

    public String groupIdOrDefault() {
        return consumer != null && consumer.groupId != null ? consumer.groupId : "pet-marketplace-animal-info";
    }

    public String autoOffsetResetOrDefault() {
        return consumer != null && consumer.autoOffsetReset != null ? consumer.autoOffsetReset : "earliest";
    }

    public int concurrencyOrDefault() {
        return consumer != null && consumer.concurrency != null ? consumer.concurrency : 1;
    }

    public String acksOrDefault() {
        return producer != null && producer.acks != null ? producer.acks : "all";
    }
}
```

- [ ] **Step 2: Add the `kafka:` block to the base section of `application.yml`**

In `src/main/resources/application.yml`, insert this block immediately after the `server:` block (i.e. after the `context-path: /api/v1` line, before `security:`):

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
    reply: ${KAFKA_TOPIC_REPLY:pet-marketplace.animal-info.replies}
```

This base block is active in the `dev` profile (and inherited by `test`/`stand`, where `application-test.yml` / `application-stand.yml` override what they need).

- [ ] **Step 3: Add the `kafka:` block to `application-test.yml`**

In `src/test/resources/application-test.yml`, append at the end of the file (the `kafka.bootstrap-servers` here is a placeholder; in embedded mode `IntegrationTestBase`'s `@DynamicPropertySource` overrides it with the Testcontainers `KafkaContainer` bootstrap servers — added in Task 7. The topic names and group match the dev defaults so the test broker uses the same contract):

```yaml
kafka:
  bootstrap-servers: placeholder-overridden-by-dynamic-property-source
  consumer:
    group-id: pet-marketplace-animal-info-test
    auto-offset-reset: earliest
    concurrency: 1
  producer:
    acks: all
  topics:
    request: pet-marketplace.animal-info.requests
    reply: pet-marketplace.animal-info.replies
```

- [ ] **Step 4: Add the `kafka:` block to `application-stand.yml`**

In `src/test/resources/application-stand.yml`, append at the end (defaults target the local docker-compose Kafka started in Task 9; override with `STAND_KAFKA_*` env vars for a remote stand — the `stand` profile overlays `test`, and its `kafka.bootstrap-servers` here overrides the Testcontainers placeholder from `application-test.yml`):

```yaml
kafka:
  bootstrap-servers: ${STAND_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  consumer:
    group-id: ${STAND_KAFKA_CONSUMER_GROUP:pet-marketplace-animal-info-test}
    auto-offset-reset: earliest
    concurrency: 1
  producer:
    acks: all
  topics:
    request: ${STAND_KAFKA_TOPIC_REQUEST:pet-marketplace.animal-info.requests}
    reply: ${STAND_KAFKA_TOPIC_REPLY:pet-marketplace.animal-info.replies}
```

- [ ] **Step 5: Verify the project still compiles and the context can bind the properties**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL (the record compiles; binding is exercised by the integration test in Task 8).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/petmarketplace/infrastructure/kafka/KafkaIntegrationProperties.java \
        src/main/resources/application.yml \
        src/test/resources/application-test.yml \
        src/test/resources/application-stand.yml
git commit -m "feat(kafka): add externalized kafka.* configuration properties"
```

---

### Task 3: KafkaConfig — factories, KafkaTemplate, JSON serdes

**Files:**
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/KafkaConfig.java`

**Interfaces:**
- Consumes: `KafkaIntegrationProperties` (Task 2), `tools.jackson.databind.json.JsonMapper` (Boot 4 auto-configured Jackson-3 bean), and the DTOs `AnimalInfoRequest` / `AnimalInfoResponse` (Task 4). **Build order note:** Task 4 (DTOs) must be done before this compiles. If executing strictly task-by-task, do Task 4 first, then Task 3. They are independent in design but `KafkaConfig` references the DTO classes. The recommended execution order is Task 1 → Task 2 → Task 4 → Task 3 → Task 5 → Task 6 → Task 7 → Task 8 → Task 9. (Tasks are numbered by logical grouping; execute in the order that satisfies compile dependencies.)
- Produces beans: `ConsumerFactory<String, AnimalInfoRequest>` (`animalInfoConsumerFactory`), `ConcurrentKafkaListenerContainerFactory<String, AnimalInfoRequest>` (`animalInfoListenerContainerFactory`), `ProducerFactory<String, AnimalInfoResponse>` (`animalInfoProducerFactory`), `KafkaTemplate<String, AnimalInfoResponse>` (`animalInfoKafkaTemplate`). Also `@EnableConfigurationProperties(KafkaIntegrationProperties.class)`.

- [ ] **Step 1: Create KafkaConfig**

`src/main/java/com/petmarketplace/infrastructure/kafka/KafkaConfig.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the animal-info request/reply Kafka integration from {@link KafkaIntegrationProperties}.
 * Uses Spring Kafka 4.0's Jackson-3 {@code JacksonJsonSerializer}/{@code JacksonJsonDeserializer}
 * (NOT the deprecated Jackson-2 variants) so the app stays on a single Jackson 3 classpath.
 *
 * Request side: a {@code @KafkaListener} consumes {@link AnimalInfoRequest} (value deserializer
 * wrapped in {@link ErrorHandlingDeserializer} so a malformed payload yields a null record value
 * plus a deserialization-exception header instead of throwing the container into retry storms).
 * Reply side: a {@link KafkaTemplate} publishes {@link AnimalInfoResponse} with no type-info header
 * (external systems must not be required to emit/read Spring Kafka type headers).
 */
@EnableKafka
@EnableConfigurationProperties(KafkaIntegrationProperties.class)
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, AnimalInfoRequest> animalInfoConsumerFactory(
            KafkaIntegrationProperties props, JsonMapper jsonMapper) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServersOrDefault());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.groupIdOrDefault());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.autoOffsetResetOrDefault());
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer swallows deserialization failures: value becomes null and the
        // exception is attached as a header (ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER),
        // so the listener can reply ERROR instead of the container looping on a poison pill.
        ErrorHandlingDeserializer<AnimalInfoRequest> valueDeserializer =
                new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(AnimalInfoRequest.class, jsonMapper));
        return new DefaultConsumerFactory<>(cfg, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "animalInfoListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, AnimalInfoRequest> animalInfoListenerContainerFactory(
            ConsumerFactory<String, AnimalInfoRequest> animalInfoConsumerFactory,
            KafkaIntegrationProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, AnimalInfoRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(animalInfoConsumerFactory);
        factory.setConcurrency(props.concurrencyOrDefault());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        // No retries (FixedBackOff(0, 0) => zero attempts); the default recoverer logs and stops. The
        // listener catches every exception itself and always replies, so this handler is only a
        // safety net — it must never redeliver a record.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }

    @Bean
    public ProducerFactory<String, AnimalInfoResponse> animalInfoProducerFactory(
            KafkaIntegrationProperties props, JsonMapper jsonMapper) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServersOrDefault());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, props.acksOrDefault());
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(cfg,
                new StringSerializer(),
                new JacksonJsonSerializer<>(jsonMapper).noTypeInfo());
    }

    @Bean(name = "animalInfoKafkaTemplate")
    public KafkaTemplate<String, AnimalInfoResponse> animalInfoKafkaTemplate(
            ProducerFactory<String, AnimalInfoResponse> animalInfoProducerFactory) {
        return new KafkaTemplate<>(animalInfoProducerFactory);
    }
}
```

> **Note for the implementer:** `JacksonJsonSerializer` and `JacksonJsonDeserializer` are Spring Kafka 4.0's Jackson-3 classes (`org.springframework.kafka.support.serializer.JacksonJsonSerializer` / `JacksonJsonDeserializer`). Confirm their exact constructor signatures against the resolved spring-kafka version (`gradle dependencyInsight --dependency spring-kafka --configuration compileClasspath` then read the Javadoc). The intended forms are: `new JacksonJsonSerializer<>(jsonMapper).noTypeInfo()` for the serializer, and `new JacksonJsonDeserializer<>(AnimalInfoRequest.class, jsonMapper)` for the deserializer (class-targeted, so no `__TypeId__` header is required on the wire). If the resolved version's constructor differs (e.g. deserializer takes `(jsonMapper, Class)` in the other order or only `Class`), adjust accordingly — the wire contract (no type headers) is what matters.

- [ ] **Step 2: Verify it compiles**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL. A compile error here almost always means the `JacksonJsonSerializer`/`JacksonJsonDeserializer` constructor signature differs from the resolved version — fix per the note above. Do NOT swap to the deprecated Jackson-2 `JsonSerializer`/`JsonDeserializer`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/petmarketplace/infrastructure/kafka/KafkaConfig.java
git commit -m "feat(kafka): add KafkaConfig with Jackson-3 serdes, consumer/producer factories"
```

---

### Task 4: DTOs and MapStruct mapper

**Files:**
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/ReplyStatus.java`
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequest.java`
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoResponse.java`
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoMapper.java`

**Interfaces:**
- Consumes: `Listing`, `ListingStatus`, `ListingGender` (domain entities/enums), `LocalizedNameResolver` (infrastructure/localization).
- Produces:
  - `ReplyStatus` enum: `OK`, `NOT_FOUND`, `ERROR`.
  - `AnimalInfoRequest(UUID listingId)` record.
  - `AnimalInfoResponse(...)` record with static factories `ok(...)`, `notFound(correlationId, listingId)`, `error(correlationId, listingId, errorMessage)`.
  - `AnimalInfoMapper.toOkResponse(Listing listing, String correlationId)` → `AnimalInfoResponse` (status `OK`).

- [ ] **Step 1: Create the `ReplyStatus` enum**

`src/main/java/com/petmarketplace/infrastructure/kafka/ReplyStatus.java`:

```java
package com.petmarketplace.infrastructure.kafka;

/**
 * Outcome discriminator carried in {@link AnimalInfoResponse#status()}. Jackson 3 serializes the
 * enum by its name ("OK" / "NOT_FOUND" / "ERROR").
 */
public enum ReplyStatus {
    OK,
    NOT_FOUND,
    ERROR
}
```

- [ ] **Step 2: Create the request DTO**

`src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequest.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import java.io.Serializable;
import java.util.UUID;

/**
 * Inbound Kafka request payload: ask for animal info by listing id. The {@code correlationId}
 * travels in a Kafka header (see {@code AnimalInfoRequestListener}), not in this body.
 */
public record AnimalInfoRequest(UUID listingId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
```

- [ ] **Step 3: Create the response DTO**

`src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoResponse.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reply payload for every outcome. {@code status} is the discriminator; animal fields are null
 * unless {@code status == OK}. {@code correlationId} mirrors the request's Kafka header for
 * logging/debug. {@code listingStatus} is the listing's own status (distinct from {@code status},
 * the reply outcome).
 */
public record AnimalInfoResponse(
        ReplyStatus status,
        String correlationId,
        UUID listingId,
        String errorMessage,
        String title,
        String categoryName,
        String breedName,
        BigDecimal price,
        String currency,
        ListingGender gender,
        Integer ageMonths,
        String color,
        BigDecimal weightKg,
        String healthInfo,
        Boolean hasVaccination,
        Boolean hasDocuments,
        String locationCountry,
        String locationCity,
        ListingStatus listingStatus
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** OK outcome populated by {@link AnimalInfoMapper#toOkResponse(Listing, String)}. */
    public static AnimalInfoResponse ok(
            String correlationId, UUID listingId, String title, String categoryName, String breedName,
            BigDecimal price, String currency, ListingGender gender, Integer ageMonths, String color,
            BigDecimal weightKg, String healthInfo, Boolean hasVaccination, Boolean hasDocuments,
            String locationCountry, String locationCity, ListingStatus listingStatus) {
        return new AnimalInfoResponse(
                ReplyStatus.OK, correlationId, listingId, null, title, categoryName, breedName,
                price, currency, gender, ageMonths, color, weightKg, healthInfo, hasVaccination,
                hasDocuments, locationCountry, locationCity, listingStatus);
    }

    /** Not found — listing missing, or present but not publicly visible. */
    public static AnimalInfoResponse notFound(String correlationId, UUID listingId) {
        return new AnimalInfoResponse(
                ReplyStatus.NOT_FOUND, correlationId, listingId, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Processing/deserialization error. errorMessage is a human-readable cause, never internal stack details. */
    public static AnimalInfoResponse error(String correlationId, UUID listingId, String errorMessage) {
        return new AnimalInfoResponse(
                ReplyStatus.ERROR, correlationId, listingId, errorMessage,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 4: Create the MapStruct mapper**

`src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoMapper.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import java.util.Locale;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Maps a {@link Listing} to an OK {@link AnimalInfoResponse}. Category/breed names are resolved to
 * Russian (the platform default — {@link LocalizedNameResolver#resolveLocale(String)} returns
 * Russian for a null language tag). The Kafka request carries no language preference, so a fixed
 * Russian locale is used; this can be made configurable later if needed.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AnimalInfoMapper {

    /**
     * Produce an OK response. {@code status} is forced to OK, {@code errorMessage} ignored (null),
     * {@code listingId} taken from the listing, {@code correlationId} from the parameter, and the
     * animal fields auto-mapped by name (title, price, currency, gender, ageMonths, color, weightKg,
     * healthInfo, hasVaccination, hasDocuments, locationCountry, locationCity).
     */
    @Mapping(target = "status", constant = "OK")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "listingId", source = "listing.id")
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "categoryName",
            expression = "java(LocalizedNameResolver.resolve(listing.getCategory().getNameRu(), listing.getCategory().getNameEn(), RUSSIAN))")
    @Mapping(target = "breedName",
            expression = "java(listing.getBreed() == null ? null : LocalizedNameResolver.resolve(listing.getBreed().getNameRu(), listing.getBreed().getNameEn(), RUSSIAN))")
    @Mapping(target = "listingStatus", source = "listing.status")
    AnimalInfoResponse toOkResponse(Listing listing, String correlationId);

    /** Fixed Russian locale (matches {@link LocalizedNameResolver}'s default for a null tag). */
    Locale RUSSIAN = LocalizedNameResolver.resolveLocale(null);
}
```

- [ ] **Step 5: Verify it compiles and MapStruct generates the impl**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL, and `build/generated/sources/annotationProcessor/java/main/com/petmarketplace/infrastructure/kafka/AnimalInfoMapperImpl.java` exists.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/petmarketplace/infrastructure/kafka/ReplyStatus.java \
        src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequest.java \
        src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoResponse.java \
        src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoMapper.java
git commit -m "feat(kafka): add AnimalInfoRequest/Response DTOs, ReplyStatus and MapStruct mapper"
```

---

### Task 5: AnimalInfoService — TDD the visibility rule

**Files:**
- Test: `src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoServiceTest.java`
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoService.java`

**Interfaces:**
- Consumes: `ListingRepository.findByIdWithSeller(UUID)` → `Optional<Listing>`; `AnimalInfoMapper.toOkResponse(Listing, String)` → `AnimalInfoResponse`; `ListingStatus`.
- Produces: `AnimalInfoService.findById(UUID listingId, String correlationId)` → `AnimalInfoResponse` (status `OK` for public listings, `NOT_FOUND` for missing or non-public).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoServiceTest.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnimalInfoServiceTest {

    private final ListingRepository listingRepository = mock(ListingRepository.class);
    private final AnimalInfoMapper animalInfoMapper = mock(AnimalInfoMapper.class);
    private final AnimalInfoService service = new AnimalInfoService(listingRepository, animalInfoMapper);

    private static final UUID LISTING_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String CORRELATION_ID = "corr-1";

    @Test
    void shouldReturnOkForActiveListing() {
        Listing listing = mock(Listing.class);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));
        AnimalInfoResponse ok = AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                null, null, null, null, null, null, null, null, null, null, null, ListingStatus.ACTIVE);
        when(animalInfoMapper.toOkResponse(listing, CORRELATION_ID)).thenReturn(ok);

        AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.OK);
        verify(animalInfoMapper).toOkResponse(listing, CORRELATION_ID);
    }

    @Test
    void shouldReturnOkForReservedAndSold() {
        for (ListingStatus status : new ListingStatus[]{ListingStatus.RESERVED, ListingStatus.SOLD}) {
            Listing listing = mock(Listing.class);
            when(listing.getStatus()).thenReturn(status);
            when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));
            when(animalInfoMapper.toOkResponse(eq(listing), eq(CORRELATION_ID)))
                    .thenReturn(AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                            null, null, null, null, null, null, null, null, null, null, null, status));

            AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);
            assertThat(result.status()).isEqualTo(ReplyStatus.OK);
        }
    }

    @Test
    void shouldReturnNotFoundWhenListingMissing() {
        when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.empty());

        AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.NOT_FOUND);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(result.listingId()).isEqualTo(LISTING_ID);
        verify(animalInfoMapper, never()).toOkResponse(any(), any());
    }

    @Test
    void shouldReturnNotFoundForNonPublicStatus() {
        for (ListingStatus hidden : new ListingStatus[]{
                ListingStatus.DRAFT, ListingStatus.PENDING_MODERATION, ListingStatus.ARCHIVED, ListingStatus.REJECTED}) {
            Listing listing = mock(Listing.class);
            when(listing.getStatus()).thenReturn(hidden);
            when(listingRepository.findByIdWithSeller(LISTING_ID)).thenReturn(Optional.of(listing));

            AnimalInfoResponse result = service.findById(LISTING_ID, CORRELATION_ID);

            assertThat(result.status())
                    .as("non-public status %s must be reported as NOT_FOUND (no existence leak)", hidden)
                    .isEqualTo(ReplyStatus.NOT_FOUND);
            verify(animalInfoMapper, never()).toOkResponse(any(), eq(CORRELATION_ID));
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle test --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoServiceTest"`
Expected: compilation FAIL — `AnimalInfoService` does not exist.

- [ ] **Step 3: Write the service**

`src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoService.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Loads a listing by id and maps it to an {@link AnimalInfoResponse}, enforcing the public-visibility
 * rule: only {@code ACTIVE}, {@code RESERVED}, {@code SOLD} are returned as OK. Any other status —
 * or a missing listing — is reported as {@link ReplyStatus#NOT_FOUND}, deliberately indistinguishable
 * from "does not exist" so the Kafka channel leaks no information about non-public listings.
 */
@Service
public class AnimalInfoService {

    private static final Set<ListingStatus> PUBLIC_STATUSES =
            EnumSet.of(ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.SOLD);

    private final ListingRepository listingRepository;
    private final AnimalInfoMapper animalInfoMapper;

    public AnimalInfoService(ListingRepository listingRepository, AnimalInfoMapper animalInfoMapper) {
        this.listingRepository = listingRepository;
        this.animalInfoMapper = animalInfoMapper;
    }

    public AnimalInfoResponse findById(UUID listingId, String correlationId) {
        Optional<Listing> listing = listingRepository.findByIdWithSeller(listingId);
        if (listing.isEmpty() || !PUBLIC_STATUSES.contains(listing.get().getStatus())) {
            return AnimalInfoResponse.notFound(correlationId, listingId);
        }
        return animalInfoMapper.toOkResponse(listing.get(), correlationId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle test --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoServiceTest"`
Expected: PASS (4 tests). This is a pure unit test (Mockito, no Spring context), so it needs neither Docker nor Kafka.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoServiceTest.java \
        src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoService.java
git commit -m "feat(kafka): add AnimalInfoService with public-status visibility rule"
```

---

### Task 6: AnimalInfoRequestListener — correlation, deserialization errors, reply

**Files:**
- Test: `src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListenerTest.java`
- Create: `src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListener.java`

**Interfaces:**
- Consumes: `AnimalInfoService.findById(UUID, String)`, `AnimalInfoResponse` factories, `KafkaTemplate<String, AnimalInfoResponse>` (bean `animalInfoKafkaTemplate`), `KafkaIntegrationProperties.topics().reply()`, `KafkaHeaders.CORRELATION_ID`, `ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER`.
- Produces: `AnimalInfoRequestListener.process(AnimalInfoRequest, String correlationId)` → `AnimalInfoResponse` (unit-testable core logic), and the `@KafkaListener` method `onRequest(...)` that reads the correlation header, detects deserialization failures, calls `process`, sends the reply with the copied correlation header, and acknowledges.

- [ ] **Step 1: Write the failing test for the core `process` method**

`src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListenerTest.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnimalInfoRequestListenerTest {

    private final AnimalInfoService service = mock(AnimalInfoService.class);
    private final AnimalInfoRequestListener listener = new AnimalInfoRequestListener(service, null);

    private static final UUID LISTING_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String CORRELATION_ID = "corr-7";

    @Test
    void processShouldDelegateToServiceForValidRequest() {
        AnimalInfoResponse ok = AnimalInfoResponse.ok(CORRELATION_ID, LISTING_ID, "T", null, null,
                null, null, null, null, null, null, null, null, null, null, null, ListingStatus.ACTIVE);
        when(service.findById(eq(LISTING_ID), eq(CORRELATION_ID))).thenReturn(ok);

        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(LISTING_ID), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.OK);
    }

    @Test
    void processShouldReturnErrorWhenListingIdMissing() {
        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(null), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void processShouldReturnErrorWhenRequestIsNull() {
        AnimalInfoResponse result = listener.process(null, CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void processShouldReturnErrorWhenServiceThrows() {
        when(service.findById(eq(LISTING_ID), eq(CORRELATION_ID)))
                .thenThrow(new RuntimeException("boom"));

        AnimalInfoResponse result = listener.process(new AnimalInfoRequest(LISTING_ID), CORRELATION_ID);

        assertThat(result.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(result.listingId()).isEqualTo(LISTING_ID);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradle test --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoRequestListenerTest"`
Expected: compilation FAIL — `AnimalInfoRequestListener` does not exist.

- [ ] **Step 3: Write the listener**

`src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListener.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumes {@link AnimalInfoRequest} from the request topic, looks up the animal, and publishes an
 * {@link AnimalInfoResponse} to the reply topic with the request's {@code correlationId} header
 * copied verbatim. Never rethrows to the container — every path ends in a reply, so the container
 * never retries/redelivers.
 */
@Component
public class AnimalInfoRequestListener {

    private static final Logger log = LoggerFactory.getLogger(AnimalInfoRequestListener.class);

    private final AnimalInfoService animalInfoService;
    private final KafkaTemplate<String, AnimalInfoResponse> replyKafkaTemplate;
    private final String replyTopic;

    public AnimalInfoRequestListener(
            AnimalInfoService animalInfoService,
            @org.springframework.beans.factory.annotation.Qualifier("animalInfoKafkaTemplate")
            KafkaTemplate<String, AnimalInfoResponse> replyKafkaTemplate,
            @Value("${kafka.topics.reply}") String replyTopic) {
        this.animalInfoService = animalInfoService;
        this.replyKafkaTemplate = replyKafkaTemplate;
        this.replyTopic = replyTopic;
    }

    /**
     * Core, unit-testable handling: turn a (possibly null) request + correlationId into a response.
     * Never throws — converts every failure into an ERROR response.
     */
    AnimalInfoResponse process(AnimalInfoRequest request, String correlationId) {
        try {
            if (request == null || request.listingId() == null) {
                return AnimalInfoResponse.error(correlationId,
                        request != null ? request.listingId() : null,
                        "Missing listingId");
            }
            return animalInfoService.findById(request.listingId(), correlationId);
        } catch (RuntimeException e) {
            log.error("Unexpected error processing animal-info request correlationId={} listingId={}",
                    correlationId, request != null ? request.listingId() : null, e);
            return AnimalInfoResponse.error(correlationId,
                    request != null ? request.listingId() : null,
                    "Processing error");
        }
    }

    @KafkaListener(
            topics = "${kafka.topics.request}",
            groupId = "${kafka.consumer.group-id}",
            containerFactory = "animalInfoListenerContainerFactory")
    public void onRequest(ConsumerRecord<String, AnimalInfoRequest> record,
                          org.springframework.kafka.support.Acknowledgment ack) {
        String correlationId = readCorrelationId(record);
        UUID safeListingId = record.value() != null ? record.value().listingId() : null;

        // Deserialization failure: ErrorHandlingDeserializer set value=null and attached the
        // exception as a header. Reply ERROR with the (possibly generated) correlationId.
        if (record.value() == null
                && record.headers().lastHeader(ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER) != null) {
            log.warn("Failed to deserialize animal-info request correlationId={}", correlationId);
            sendReply(correlationId,
                    AnimalInfoResponse.error(correlationId, null, "Failed to deserialize request"));
            ack.acknowledge();
            return;
        }

        AnimalInfoResponse response = process(record.value(), correlationId);
        switch (response.status()) {
            case OK -> log.info("Animal-info OK correlationId={} listingId={}", correlationId, safeListingId);
            case NOT_FOUND -> log.warn("Animal-info NOT_FOUND correlationId={} listingId={}", correlationId, safeListingId);
            case ERROR -> log.error("Animal-info ERROR correlationId={} listingId={} message={}",
                    correlationId, safeListingId, response.errorMessage());
        }
        sendReply(correlationId, response);
        ack.acknowledge();
    }

    private String readCorrelationId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.CORRELATION_ID);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        // No correlation header (malformed producer): mint one so a reply is never dropped.
        String generated = UUID.randomUUID().toString();
        log.warn("Animal-info request had no {} header; generated correlationId={}",
                KafkaHeaders.CORRELATION_ID, generated);
        return generated;
    }

    private void sendReply(String correlationId, AnimalInfoResponse response) {
        org.apache.kafka.clients.producer.ProducerRecord<String, AnimalInfoResponse> reply =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        replyTopic, null, null, response,
                        List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID,
                                correlationId.getBytes(StandardCharsets.UTF_8))));
        replyKafkaTemplate.send(reply);
    }
}
```

> **Note:** `onRequest` uses `org.springframework.kafka.support.Acknowledgment` (Spring Kafka's manual-ack handle) — that is the correct type for an `AckMode.RECORD` listener; do not confuse it with `org.apache.kafka.clients.consumer.OffsetCommitCallback`. The `process` method is package-private so the unit test can call it directly.

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `gradle test --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoRequestListenerTest"`
Expected: PASS (4 tests). The test passes `null` for the KafkaTemplate (the `process` method never uses it), so the constructor must tolerate a null template — it does (the template is only used in `onRequest`, which the unit test does not call). The full `onRequest` flow is verified end-to-end by the integration test in Task 8.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListenerTest.java \
        src/main/java/com/petmarketplace/infrastructure/kafka/AnimalInfoRequestListener.java
git commit -m "feat(kafka): add AnimalInfoRequestListener with correlation + error handling"
```

---

### Task 7: Wire KafkaContainer into IntegrationTestBase

**Files:**
- Modify: `src/test/java/com/petmarketplace/IntegrationTestBase.java`

**Interfaces:**
- Consumes: `org.testcontainers.kafka.KafkaContainer`, `org.testcontainers.utility.DockerImageName`, the existing shared-container static-block pattern and `@DynamicPropertySource` guard.
- Produces: a JVM-shared `KafkaContainer` started once in embedded mode, and `kafka.bootstrap-servers` bound to it via `@DynamicPropertySource` (embedded only — stand mode reads `STAND_KAFKA_BOOTSTRAP_SERVERS` from the `stand` profile).

- [ ] **Step 1: Add the KafkaContainer and its imports**

At the top of `IntegrationTestBase.java`, add these imports alongside the existing ones (do not remove existing imports):

```java
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
```

In the existing static field section (near `POSTGRES_IMAGE` / `REDIS_IMAGE`), add:

```java
    private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.1";
```

Near the `POSTGRES` / `REDIS` container fields, add:

```java
    // Shared Kafka broker for the animal-info request/reply integration tests. Same shared-container
    // rationale as POSTGRES/REDIS: started once per JVM in the static block (NOT @Container), so the
    // cached Spring context's @KafkaListener container keeps a stable bootstrap address across test
    // classes. In stand mode this is never started (the stand profile supplies kafka.bootstrap-servers).
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
```

- [ ] **Step 2: Start Kafka in the existing static block**

Modify the existing static block to also start `KAFKA` inside the same `try` (so a Kafka start failure flips `dockerAvailable` to false and `TestModeCondition` skips the suite cleanly — consistent with the Postgres/Redis failure behaviour):

```java
    static {
        if (!STAND_MODE) {
            try {
                POSTGRES.start();
                REDIS.start();
                KAFKA.start();
                dockerAvailable = true;
            } catch (Exception e) {
                // Docker unavailable: leave containers unstarted. TestModeCondition then disables
                // every test before the context is created, so the suppliers below are never read.
                dockerAvailable = false;
            }
        }
    }
```

- [ ] **Step 3: Bind `kafka.bootstrap-servers` in `@DynamicPropertySource`**

In the existing `configureProperties(DynamicPropertyRegistry registry)` method, inside the `if (STAND_MODE) { return; }` guard — add the kafka binding alongside the existing `spring.datasource.*` / `spring.data.redis.*` bindings (so stand mode is untouched and keeps reading `STAND_KAFKA_BOOTSTRAP_SERVERS`):

```java
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
```

- [ ] **Step 4: Verify it compiles and the existing test suite still skips cleanly without Docker**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL.

(Do not run the full `gradle test` yet — the integration test for Kafka is added in Task 8. If Docker is available, you may run a single existing test to confirm nothing regressed: `gradle test --tests "com.petmarketplace.application.listing.controller.ListingControllerTest"`. Expected PASS.)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/petmarketplace/IntegrationTestBase.java
git commit -m "test(kafka): share a KafkaContainer in IntegrationTestBase (embedded mode)"
```

---

### Task 8: AnimalInfoKafkaIntegrationTest — end-to-end request/reply

**Files:**
- Test: `src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoKafkaIntegrationTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase` (creates a SELLER + ACTIVE listing via the existing `createActiveListing` helper, plus a DRAFT listing via the REST create endpoint without moderation), `KafkaTemplate`, `KafkaHeaders.CORRELATION_ID`, the `kafka.topics.request` / `kafka.topics.reply` / `kafka.bootstrap-servers` properties, `AnimalInfoRequest` / `AnimalInfoResponse`, Jackson-3 `JacksonJsonSerializer`/`JacksonJsonDeserializer` for the test producer/consumer.
- Produces: a passing integration test that runs in both embedded (Testcontainers) and stand modes, exercising OK / NOT_FOUND / ERROR outcomes and asserting the correlation header round-trips.

- [ ] **Step 1: Write the integration test**

`src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoKafkaIntegrationTest.java`:

```java
package com.petmarketplace.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

class AnimalInfoKafkaIntegrationTest extends IntegrationTestBase {

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${kafka.topics.request}")
    private String requestTopic;
    @Value("${kafka.topics.reply}")
    private String replyTopic;

    private org.springframework.kafka.core.KafkaTemplate<String, AnimalInfoRequest> requestTemplate;
    private KafkaConsumer<String, AnimalInfoResponse> replyConsumer;
    private org.springframework.kafka.core.ProducerFactory<String, AnimalInfoRequest> requestProducerFactory;

    @BeforeEach
    void setUpKafkaClients(org.springframework.beans.factory.annotation.Autowired JsonMapper jsonMapper) {
        // Request producer: String keys, AnimalInfoRequest values, no type-info header on the wire.
        Map<String, Object> producerCfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        requestProducerFactory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(
                producerCfg, new StringSerializer(),
                new JacksonJsonSerializer<>(jsonMapper).noTypeInfo());
        requestTemplate = new org.springframework.kafka.core.KafkaTemplate<>(requestProducerFactory);

        // Reply consumer: unique group, manual assign + seekToBeginning so we never miss a reply
        // produced before this consumer's first poll.
        Map<String, Object> consumerCfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reply-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        replyConsumer = new KafkaConsumer<>(consumerCfg,
                new StringDeserializer(),
                new JacksonJsonDeserializer<>(AnimalInfoResponse.class, jsonMapper));
    }

    @AfterEach
    void closeClients() {
        if (replyConsumer != null) replyConsumer.close();
        if (requestProducerFactory != null) requestProducerFactory.close();
    }

    @Test
    void shouldReplyOkForActiveListing() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);
        ListingResponse listing = createActiveListing(seller, admin);

        AnimalInfoResponse reply = requestAndAwaitReply(listing.id());

        assertThat(reply.status()).isEqualTo(ReplyStatus.OK);
        assertThat(reply.listingId()).isEqualTo(listing.id());
        assertThat(reply.title()).isEqualTo("Test puppy");
        assertThat(reply.categoryName()).isEqualTo("Собаки");
        assertThat(reply.breedName()).isEqualTo("Лабрадор-ретривер");
        assertThat(reply.listingStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(reply.price()).isEqualByComparingTo(BigDecimal.valueOf(30000));
        assertThat(reply.currency()).isEqualTo("RUB");
        assertThat(reply.gender()).isEqualTo(ListingGender.MALE);
        assertThat(reply.hasVaccination()).isTrue();
        assertThat(reply.hasDocuments()).isTrue();
        assertThat(reply.locationCity()).isEqualTo("Moscow");
        assertThat(reply.locationCountry()).isEqualTo("Russia");
    }

    @Test
    void shouldReplyOkForReservedAndSoldListings() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);
        // createActiveListing returns ACTIVE; we then flip status via the admin moderate endpoint.
        ListingResponse active = createActiveListing(seller, admin);
        for (ListingStatus target : new ListingStatus[]{ListingStatus.RESERVED, ListingStatus.SOLD}) {
            restClient.put().uri("/admin/listings/" + active.id() + "/moderate")
                    .body(new com.petmarketplace.application.admin.dto.ListingModerateRequest(target, "set " + target))
                    .headers(authHeaders(admin))
                    .retrieve().toEntity(ListingResponse.class);

            AnimalInfoResponse reply = requestAndAwaitReply(active.id());
            assertThat(reply.status()).isEqualTo(ReplyStatus.OK);
            assertThat(reply.listingStatus()).isEqualTo(target);
        }
    }

    @Test
    void shouldReplyNotFoundForUnknownListingId() {
        UUID unknown = UUID.randomUUID();
        AnimalInfoResponse reply = requestAndAwaitReply(unknown);
        assertThat(reply.status()).isEqualTo(ReplyStatus.NOT_FOUND);
        assertThat(reply.listingId()).isEqualTo(unknown);
    }

    @Test
    void shouldReplyNotFoundForDraftListing() {
        TestUser seller = createUniqueUser(Role.SELLER);
        // Create via REST but do NOT moderate → stays DRAFT.
        ListingCreateRequest createRequest = new ListingCreateRequest(
                DOGS_CATEGORY_ID, LABRADOR_BREED_ID, "Draft pet", "desc",
                BigDecimal.valueOf(1000), "RUB", ListingGender.FEMALE, 2, "White",
                BigDecimal.valueOf(5.0), "ok", false, false, "Russia", "Kazan");
        ListingResponse draft = restClient.post().uri("/listings")
                .body(createRequest).headers(authHeaders(seller))
                .retrieve().toEntity(ListingResponse.class).getBody();
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo(ListingStatus.PENDING_MODERATION);

        AnimalInfoResponse reply = requestAndAwaitReply(draft.id());
        assertThat(reply.status()).isEqualTo(ReplyStatus.NOT_FOUND);
    }

    @Test
    void shouldReplyErrorForMalformedRequestBody() {
        // Produce raw bytes that are not valid JSON for AnimalInfoRequest. The consumer's
        // ErrorHandlingDeserializer will set value=null + attach the exception header, and the
        // listener replies ERROR.
        String correlationId = UUID.randomUUID().toString();
        org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> badFactory =
                new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                               ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                        new StringSerializer(), new StringSerializer());
        org.springframework.kafka.core.KafkaTemplate<String, String> badTemplate =
                new org.springframework.kafka.core.KafkaTemplate<>(badFactory);
        badTemplate.send(new ProducerRecord<>(requestTopic, null, null, "this is not json",
                List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID, correlationId.getBytes()))));
        badTemplate.flush();
        badFactory.close();

        AnimalInfoResponse reply = awaitReply(correlationId, Duration.ofSeconds(20));
        assertThat(reply.status()).isEqualTo(ReplyStatus.ERROR);
        assertThat(reply.correlationId()).isEqualTo(correlationId);
    }

    /** Produce an AnimalInfoRequest with the given correlationId header and poll the reply topic. */
    private AnimalInfoResponse requestAndAwaitReply(UUID listingId) {
        String correlationId = UUID.randomUUID().toString();
        ProducerRecord<String, AnimalInfoRequest> request = new ProducerRecord<>(
                requestTopic, null, null, new AnimalInfoRequest(listingId),
                List.of(new RecordHeader(KafkaHeaders.CORRELATION_ID, correlationId.getBytes())));
        requestTemplate.send(request);
        requestTemplate.flush();
        return awaitReply(correlationId, Duration.ofSeconds(20));
    }

    /** Manually assign reply partition 0, seek to beginning, then poll until a matching reply arrives. */
    private AnimalInfoResponse awaitReply(String correlationId, Duration timeout) {
        org.apache.kafka.common.TopicPartition partition = new org.apache.kafka.common.TopicPartition(replyTopic, 0);
        replyConsumer.assign(List.of(partition));
        replyConsumer.seekToBeginning(List.of(partition));
        AtomicReference<AnimalInfoResponse> found = new AtomicReference<>();
        await().atMost(timeout).until(() -> {
            for (ConsumerRecord<String, AnimalInfoResponse> rec : replyConsumer.poll(Duration.ofSeconds(2))) {
                String recCorr = rec.headers().lastHeader(KafkaHeaders.CORRELATION_ID);
                if (recCorr != null && correlationId.equals(new String(recCorr.value()))) {
                    found.set(rec.value());
                    return true;
                }
            }
            return false;
        });
        AnimalInfoResponse r = found.get();
        assertThat(r).as("reply for correlationId %s", correlationId).isNotNull();
        return r;
    }
}
```

> **Notes for the implementer:**
> - `org.awaitility.Awaitility` — confirm it is on the test classpath. Spring Boot's `spring-boot-starter-test` transitively brings Awaitility (`org.awaitility:awaitility`). If `import org.awaitility.Awaitility;` fails to resolve, add `testImplementation("org.awaitility:awaitility")` to `build.gradle.kts` in this task and commit it together. Do not replace Awaitility with a hand-rolled sleep loop.
> - The `createActiveListing(seller, admin)` helper (in `IntegrationTestBase`) creates a listing titled `"Test puppy"` at price `30000`, gender `MALE`, city `Moscow`, country `Russia`, with the seeded Dogs category (RU name `Собаки`) and Labrador breed (RU name `Лабрадор-ретривер`) — these constants are asserted in `shouldReplyOkForActiveListing`. If that helper's defaults ever change, update the assertions to match.
> - Confirm the `JacksonJsonSerializer` / `JacksonJsonDeserializer` constructor signatures match the resolved spring-kafka version (same note as Task 3). The wire contract is: request/reply values are plain JSON with **no** `__TypeId__` header.
> - In stand mode this test class targets the **stand's** Kafka (`STAND_KAFKA_BOOTSTRAP_SERVERS`, default `localhost:9092` from the compose Kafka added in Task 9). For `gradle testOnStand` to pass, the stand's Kafka must be running — documented in Task 9.

- [ ] **Step 2: Run the integration test (embedded mode)**

Run: `gradle test --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoKafkaIntegrationTest"`
Expected: PASS (5 tests). Requires Docker (Testcontainers). If skipped because Docker is unavailable, start Docker Desktop and rerun.

- [ ] **Step 3: Run the same suite in stand mode**

Prerequisite: the stand (Postgres + Redis + Kafka + app) must be running locally — `docker-compose up -d && gradle bootRun` in a separate terminal (the Kafka service is added in Task 9; run Task 9 first if not yet done). Then:

Run: `gradle testOnStand --tests "com.petmarketplace.infrastructure.kafka.AnimalInfoKafkaIntegrationTest"`
Expected: PASS (5 tests). If the listener fails to connect to Kafka, confirm the compose Kafka is up (`docker ps` shows `petmarketplace-kafka`) and `STAND_KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`) is correct.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/petmarketplace/infrastructure/kafka/AnimalInfoKafkaIntegrationTest.java build.gradle.kts
git commit -m "test(kafka): add AnimalInfoKafkaIntegrationTest (OK/NOT_FOUND/ERROR, both modes)"
```

---

### Task 9: docker-compose Kafka service + docs

**Files:**
- Modify: `docker-compose.yml`
- Modify: `CLAUDE.md` (document the Kafka integration, the `kafka.*` properties, and the stand-mode Kafka requirement)
- Modify: `README.md` (add a short "Kafka integration" subsection)

**Interfaces:**
- Consumes: the `kafka.*` defaults (Task 2) — the compose Kafka listens on `localhost:9092` to match `KAFKA_BOOTSTRAP_SERVERS` / `STAND_KAFKA_BOOTSTRAP_SERVERS` defaults.
- Produces: a KRaft-mode Kafka service in compose (so `docker-compose up -d` brings the broker up), and project docs that mention the integration, its topics, properties, and the two test modes.

- [ ] **Step 1: Add the Kafka service to docker-compose.yml**

In `docker-compose.yml`, add a `kafka` service (KRaft single-node, no Zookeeper) after the `mailpit` service, inside the existing `services:` block. Use these KRaft environment variables (Confluent image, matching the `confluentinc/cp-kafka:7.6.1` used by Testcontainers in Task 7 so the test broker and the stand broker are the same image):

```yaml
  kafka:
    image: confluentinc/cp-kafka:7.6.1
    container_name: petmarketplace-kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 10s
      timeout: 10s
      retries: 10
    networks:
      - petmarketplace-network
```

> `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` enforces the spec's "the app does not auto-create topics" — the two topics must exist for the listener to consume. Provision them once (Step 3) with the Kafka CLI; in production, the operator does the equivalent. The integration tests provision their own topics via the Testcontainers/Kafka APIs (the `KafkaContainer` default creates topics on demand only if auto-create is on; since our `application-test.yml` does not disable auto-create on the container, and the test uses a fresh group with `auto.offset.reset=earliest`, the topics are created implicitly when first produced to — which is fine for tests).

- [ ] **Step 2: Verify compose starts the broker**

Run: `docker-compose up -d kafka`
Expected: `petmarketplace-kafka` becomes healthy (`docker ps` shows a healthy status after ~20s). Then verify connectivity:

```bash
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 --list
```
Expected: empty list (no topics yet). Tear down after: `docker-compose down`.

- [ ] **Step 3: Provision the two topics on the running broker**

With the broker up, create the request and reply topics (run from the project root; the topic names match `KAFKA_TOPIC_REQUEST` / `KAFKA_TOPIC_REPLY` defaults from Task 2):

```bash
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic pet-marketplace.animal-info.requests --partitions 1 --replication-factor 1
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --topic pet-marketplace.animal-info.replies --partitions 1 --replication-factor 1
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 --list
```
Expected: the `--list` output includes both topic names. (Document this in README in Step 5 — operators must run the equivalent for non-compose deployments.)

- [ ] **Step 4: Document in CLAUDE.md**

In `CLAUDE.md`, add a new `## Kafka Integration` section after the `## File Storage` section, covering: the animal-info request/reply flow (request topic → `@KafkaListener` → reply topic, correlation by `KafkaHeaders.CORRELATION_ID`), the `kafka.*` property namespace + env-var overrides, the `infrastructure/kafka` package contents, the public-status visibility rule, the "topics are not auto-created" operator note, and the stand-mode requirement that the compose Kafka must be running for `gradle testOnStand`. Mirror the existing CLAUDE.md style (concise, command-oriented). Example content:

```markdown
## Kafka Integration

A request/reply integration under `infrastructure/kafka`: an external system produces an
`{ "listingId": "<uuid>" }` message (with a `correlationId` Kafka header) to the request topic, the
app's `@KafkaListener` (`AnimalInfoRequestListener`) looks up the listing via `AnimalInfoService`,
and publishes an `AnimalInfoResponse` (status `OK` / `NOT_FOUND` / `ERROR`) to the reply topic with the
same `correlationId` header. Only `ACTIVE` / `RESERVED` / `SOLD` listings are returned as `OK`;
everything else (missing or non-public) is `NOT_FOUND`. All wiring is externalized under the `kafka.*`
properties (broker, consumer/producer settings, topic names), overridable via `KAFKA_*` env vars.

Topics are NOT auto-created by the app (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` in compose). Create
them on any deployment before first use:

    docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
      --create --topic pet-marketplace.animal-info.requests --partitions 1 --replication-factor 1
    docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
      --create --topic pet-marketplace.animal-info.replies --partitions 1 --replication-factor 1

Tests: `gradle test` starts a shared `KafkaContainer` (Testcontainers) in `IntegrationTestBase`
and binds `kafka.bootstrap-servers` to it. `gradle testOnStand` targets the stand's Kafka — the
compose `kafka` service must be running first (`docker-compose up -d kafka`), or `STAND_KAFKA_*`
env vars must point at a remote broker.
```

- [ ] **Step 5: Document in README.md**

Read the current `README.md` and add a short `## Kafka integration` subsection (place it after the existing Authentication / stand sections) summarizing the request/reply contract, the two topic names, the `correlationId` header, the `kafka.*` config keys, and the topic-provisioning commands from Step 3. Match the README's existing tone.

- [ ] **Step 6: Final full-suite verification (embedded mode)**

Run: `gradle test`
Expected: all tests PASS (the existing 72 plus the new AnimalInfoServiceTest (4), AnimalInfoRequestListenerTest (4), and AnimalInfoKafkaIntegrationTest (5) = 85). Requires Docker.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml CLAUDE.md README.md
git commit -m "feat(kafka): add docker-compose kafka service and docs"
```

---

## Final verification checklist (after all tasks)

- [ ] `gradle build -x test` succeeds.
- [ ] `gradle test` passes (embedded, Docker available) — 85 tests.
- [ ] With the stand up (`docker-compose up -d && gradle bootRun`) and topics provisioned, `gradle testOnStand` passes.
- [ ] `git log --oneline` shows one commit per task.
- [ ] No Jackson 2 dependency was introduced (`gradle dependencyInsight --dependency jackson-databind --configuration runtimeClasspath` shows only `tools.jackson` artifacts, or the Jackson 2 one only as a transitive of a non-JSON library — verify no `com.fasterxml.jackson.databind` reaches the Kafka serdes).