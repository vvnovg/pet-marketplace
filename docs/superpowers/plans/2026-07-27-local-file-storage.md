# Локальное файловое хранилище — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заставить загрузку файлов действительно работать: реализовать `LocalFileStorageService`, отдавать сохранённое через новый эндпоинт бэкенда и сделать проксирующий маршрут фронтенда бинарно-безопасным.

**Architecture:** Файлы кладутся на диск в `{storage.local.base-path}/{bucket}/{objectKey}`. `store()` возвращает корне-относительный URL `{storage.public-base-path}/{bucket}/{objectKey}` (по умолчанию `/api/proxy/files/...`), который браузер запрашивает у фронтенда; тот проксирует на `GET /api/v1/files/{bucket}/{objectKey}`. Новых публичных портов не появляется — бэкенд остаётся закрытым файрволом.

**Tech Stack:** Spring Boot 4, Java 26, Lombok, `java.nio.file`, Spring Security; фронтенд — Next.js 15 route handler, Vitest + MSW.

## Global Constraints

- Спек: `docs/superpowers/specs/2026-07-27-local-file-storage-design.md`.
- Два репозитория: бэкенд `/Users/vvnovg/pet-marketplace` (ветка `feat/local-file-storage`), фронтенд `/Users/vvnovg/pet-marketplace-front` (ветка от `main`, создаётся в Task 3). Коммиты делаются в том репозитории, файлы которого менялись.
- Сигнатуры интерфейса `FileStorageService` не меняются: `String store(String, String, InputStream, long, String)`, `InputStream retrieve(String, String)`, `void delete(String, String)`, `String getPublicUrl(String, String)`.
- Защита от обхода пути применяется во **всех четырёх** методах, а не только в чтении: `objectKey` попадает в сервис прямо из URL запроса.
- Права: `/files/avatars/**` и `/files/images/**` — `permitAll`; `/files/messages/**` — `authenticated`.
- Новое свойство `storage.public-base-path` со значением по умолчанию `/api/proxy/files`; переопределяется переменной `STORAGE_PUBLIC_BASE_PATH`.
- `ValidationException` имеет **только** конструктор от одной строки; `BusinessException` — от строки и от строки с причиной. Бизнес-ошибки → HTTP 409, `ValidationException` → 400, `ResourceNotFoundException` → 404.
- Никаких новых зависимостей ни в одном репозитории.
- `docker-compose.yml` не меняется: том `app-uploads` и `STORAGE_LOCAL_PATH: /app/uploads` уже на месте.
- Java-команды: системный `gradle` (не `./gradlew` — обёртка нацелена на Gradle 8.14 и не запускается на JDK 26). `gradle test` требует запущенного Docker.
- Фронтенд-команды: `pnpm test`, `pnpm exec tsc --noEmit`, `pnpm build`, `pnpm exec playwright test`.

---

### Task 1: `LocalFileStorageService` — реальная реализация

**Files:**
- Modify: `/Users/vvnovg/pet-marketplace/src/main/java/com/petmarketplace/infrastructure/storage/LocalFileStorageService.java` (полная замена)
- Modify: `/Users/vvnovg/pet-marketplace/src/main/java/com/petmarketplace/infrastructure/storage/MinioFileStorageService.java`
- Modify: `/Users/vvnovg/pet-marketplace/src/main/resources/application.yml:94-98`
- Modify: `/Users/vvnovg/pet-marketplace/src/test/resources/application-test.yml`
- Test: `/Users/vvnovg/pet-marketplace/src/test/java/com/petmarketplace/infrastructure/storage/LocalFileStorageServiceTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces:
  - `LocalFileStorageService` — bean, активный при `storage.provider=local` (и при отсутствии свойства). Конструктор: `LocalFileStorageService(String basePath, String publicBasePath)` через `@Value`.
  - `store(...)` возвращает `{publicBasePath}/{bucket}/{objectKey}`.
  - `retrieve(...)` бросает `ResourceNotFoundException`, если файла нет.
  - Некорректный `objectKey` (пустой, с `..`, абсолютный) → `ValidationException` во всех четырёх методах.
  - Свойство `storage.public-base-path` доступно всем профилям.

- [ ] **Step 1: Добавить свойства конфигурации**

В `src/main/resources/application.yml` заменить блок `storage`:

```yaml
storage:
  provider: local
  # Префикс, из которого собирается публичный URL файла. По умолчанию указывает на
  # проксирующий маршрут фронтенда: бэкенд закрыт файрволом, и браузер ходит только
  # на фронтенд.
  public-base-path: ${STORAGE_PUBLIC_BASE_PATH:/api/proxy/files}
  local:
    base-path: ${STORAGE_LOCAL_PATH:./uploads}
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket-name: petmarketplace
```

`application-prod.yml` не трогаем: его блок `storage` переопределяет только `provider` и `local.base-path`, а `public-base-path` наследуется из `application.yml`.

В `src/test/resources/application-test.yml` дописать в конец файла:

```yaml
storage:
  local:
    base-path: build/test-uploads
```

- [ ] **Step 2: Написать падающие тесты**

Создать `src/test/java/com/petmarketplace/infrastructure/storage/LocalFileStorageServiceTest.java`:

```java
package com.petmarketplace.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit test: no Spring context, the service is constructed directly. */
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalFileStorageService(tempDir.toString(), "/api/proxy/files");
    }

    @AfterEach
    void tearDown() {
        // @TempDir cleans itself up; nothing to do.
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeWritesFileAndReturnsPublicUrl() throws Exception {
        String key = "avatars/" + UUID.randomUUID() + "/pic.png";

        String url = service.store("avatars", key, bytes("hello"), 5, "image/png");

        assertThat(url).isEqualTo("/api/proxy/files/avatars/" + key);
        Path written = tempDir.resolve("avatars").resolve(key);
        assertThat(written).exists();
        assertThat(Files.readString(written)).isEqualTo("hello");
    }

    @Test
    void storeCreatesMissingParentDirectories() {
        String key = "deep/nested/path/" + UUID.randomUUID() + ".png";

        service.store("images", key, bytes("x"), 1, "image/png");

        assertThat(tempDir.resolve("images").resolve(key)).exists();
    }

    @Test
    void storeOverwritesAnExistingFile() throws Exception {
        String key = "avatars/same.png";
        service.store("avatars", key, bytes("first"), 5, "image/png");

        service.store("avatars", key, bytes("second"), 6, "image/png");

        assertThat(Files.readString(tempDir.resolve("avatars").resolve(key))).isEqualTo("second");
    }

    @Test
    void retrieveReturnsExactlyTheStoredBytes() throws Exception {
        String key = "images/listing.png";
        byte[] payload = {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 127};
        service.store("images", key, new ByteArrayInputStream(payload), payload.length, "image/png");

        try (InputStream in = service.retrieve("images", key)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void retrieveThrowsNotFoundForMissingFile() {
        assertThatThrownBy(() -> service.retrieve("avatars", "nope/missing.png"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRemovesTheFile() {
        String key = "avatars/gone.png";
        service.store("avatars", key, bytes("x"), 1, "image/png");

        service.delete("avatars", key);

        assertThat(tempDir.resolve("avatars").resolve(key)).doesNotExist();
    }

    @Test
    void deleteIsSilentForMissingFile() {
        service.delete("avatars", "never/existed.png");
        // no exception expected
    }

    @Test
    void getPublicUrlJoinsPrefixBucketAndKey() {
        assertThat(service.getPublicUrl("images", "listings/abc/def.png"))
                .isEqualTo("/api/proxy/files/images/listings/abc/def.png");
    }

    @Test
    void publicUrlPrefixTrailingSlashIsNormalised() {
        var withSlash = new LocalFileStorageService(tempDir.toString(), "/api/proxy/files/");

        assertThat(withSlash.getPublicUrl("avatars", "a.png"))
                .isEqualTo("/api/proxy/files/avatars/a.png");
    }

    @Test
    void traversingObjectKeyIsRejectedByEveryMethod() {
        String escape = "../../../etc/passwd";

        assertThatThrownBy(() -> service.store("avatars", escape, bytes("x"), 1, "image/png"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.retrieve("avatars", escape))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.delete("avatars", escape))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getPublicUrl("avatars", escape))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void absoluteObjectKeyIsRejected() {
        assertThatThrownBy(() -> service.retrieve("avatars", "/etc/passwd"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void blankBucketOrKeyIsRejected() {
        assertThatThrownBy(() -> service.retrieve("", "a.png"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.retrieve("avatars", "  "))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void traversalThatStaysInsideBaseIsAllowed() throws Exception {
        // "a/../b.png" normalises to "b.png", which is still under the base path.
        service.store("avatars", "a/../b.png", bytes("ok"), 2, "image/png");

        assertThat(Files.readString(tempDir.resolve("avatars").resolve("b.png"))).isEqualTo("ok");
    }
}
```

- [ ] **Step 3: Запустить тесты и убедиться, что они падают**

Из `/Users/vvnovg/pet-marketplace`:

```bash
gradle test --tests "com.petmarketplace.infrastructure.storage.LocalFileStorageServiceTest"
```

Ожидается: ошибка **компиляции** — `constructor LocalFileStorageService in class LocalFileStorageService cannot be applied to given types` (текущий класс имеет только конструктор по умолчанию).

- [ ] **Step 4: Реализовать сервис**

Заменить содержимое `src/main/java/com/petmarketplace/infrastructure/storage/LocalFileStorageService.java` целиком:

```java
package com.petmarketplace.infrastructure.storage;

import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path basePath;
    private final String publicBasePath;

    public LocalFileStorageService(
            @Value("${storage.local.base-path}") String basePath,
            @Value("${storage.public-base-path}") String publicBasePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.publicBasePath = publicBasePath.endsWith("/")
                ? publicBasePath.substring(0, publicBasePath.length() - 1)
                : publicBasePath;
    }

    @Override
    public String store(String bucketName, String objectKey, InputStream data, long size, String contentType) {
        Path target = resolve(bucketName, objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException("Failed to store object %s/%s".formatted(bucketName, objectKey), ex);
        }
        return getPublicUrl(bucketName, objectKey);
    }

    @Override
    public InputStream retrieve(String bucketName, String objectKey) {
        Path target = resolve(bucketName, objectKey);
        if (!Files.isRegularFile(target)) {
            throw new ResourceNotFoundException("File not found: %s/%s".formatted(bucketName, objectKey));
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw new BusinessException("Failed to read object %s/%s".formatted(bucketName, objectKey), ex);
        }
    }

    @Override
    public void delete(String bucketName, String objectKey) {
        Path target = resolve(bucketName, objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new BusinessException("Failed to delete object %s/%s".formatted(bucketName, objectKey), ex);
        }
    }

    @Override
    public String getPublicUrl(String bucketName, String objectKey) {
        // Validate here too: a traversing key must never reach the database as a URL,
        // even though building the string touches no filesystem.
        resolve(bucketName, objectKey);
        return "%s/%s/%s".formatted(publicBasePath, bucketName, objectKey);
    }

    /**
     * Resolves {@code {bucket}/{key}} under the base path and refuses anything that escapes it.
     *
     * <p>{@code objectKey} reaches this class straight from a request URL in {@code FileController},
     * so the check runs on every operation rather than only on reads.
     */
    private Path resolve(String bucketName, String objectKey) {
        if (!StringUtils.hasText(bucketName) || !StringUtils.hasText(objectKey)) {
            throw new ValidationException("Bucket name and object key are required");
        }
        Path target;
        try {
            target = basePath.resolve(bucketName).resolve(objectKey).normalize();
        } catch (InvalidPathException ex) {
            throw new ValidationException("Invalid object key: " + objectKey);
        }
        if (!target.startsWith(basePath)) {
            throw new ValidationException("Invalid object key: " + objectKey);
        }
        return target;
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что они проходят**

```bash
gradle test --tests "com.petmarketplace.infrastructure.storage.LocalFileStorageServiceTest"
```

Ожидается: BUILD SUCCESSFUL, все тесты класса зелёные.

- [ ] **Step 6: Заставить MinIO-заглушку падать громко**

Заменить два метода в `src/main/java/com/petmarketplace/infrastructure/storage/MinioFileStorageService.java`:

```java
    @Override
    public String store(String bucketName, String objectKey, InputStream data, long size, String contentType) {
        // Возврат пустой строки заставлял вызывающий код записать пустой URL и ответить
        // 200 при отсутствующем файле. Пока реализации нет, отказ должен быть явным.
        throw new UnsupportedOperationException(
                "MinIO storage is not implemented; set storage.provider=local");
    }
```

```java
    @Override
    public String getPublicUrl(String bucketName, String objectKey) {
        throw new UnsupportedOperationException(
                "MinIO storage is not implemented; set storage.provider=local");
    }
```

`retrieve` и `delete` не трогаем: первый уже бросает исключение, второй лишь логирует и никого не вводит в заблуждение.

- [ ] **Step 7: Прогнать весь бэкенд-сьют**

```bash
gradle test --rerun-tasks
```

Ожидается: BUILD SUCCESSFUL. Если сообщается «0 tests», Testcontainers не видит Docker — см. раздел Common Commands в `CLAUDE.md`, это проблема окружения, а не кода.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vvnovg/pet-marketplace && git add src/main/java/com/petmarketplace/infrastructure/storage src/main/resources/application.yml src/test/resources/application-test.yml src/test/java/com/petmarketplace/infrastructure/storage && git commit -m "feat(storage): implement local file storage and fail loudly on the MinIO stub"
```

---

### Task 2: Эндпоинт отдачи файлов и права доступа

**Files:**
- Create: `/Users/vvnovg/pet-marketplace/src/main/java/com/petmarketplace/application/file/controller/FileController.java`
- Modify: `/Users/vvnovg/pet-marketplace/src/main/java/com/petmarketplace/infrastructure/security/SecurityConfig.java:60-62`
- Modify: `/Users/vvnovg/pet-marketplace/src/main/java/com/petmarketplace/application/listing/service/ListingService.java:59`
- Test: `/Users/vvnovg/pet-marketplace/src/test/java/com/petmarketplace/application/file/controller/FileControllerTest.java`

**Interfaces:**
- Consumes: `FileStorageService.retrieve(String, String)` (Task 1), бросающий `ResourceNotFoundException` и `ValidationException`.
- Produces: `GET /files/{bucket}/{*objectKey}` → тело файла, `Content-Type` из расширения, длительный `Cache-Control`. Константа `ListingService.IMAGES_BUCKET` меняет значение с `"petmarketplace"` на `"images"`.

**Замечание о `..` в URL.** Spring Security по умолчанию использует `StrictHttpFirewall`, который отклоняет запросы с `..` в пути до того, как они дойдут до контроллера. Это тоже 400 — тот же код, что и у `ValidationException`. Поэтому тест утверждает именно 400, не различая, кто именно отказал.

- [ ] **Step 1: Написать падающие тесты**

Создать `src/test/java/com/petmarketplace/application/file/controller/FileControllerTest.java`:

```java
package com.petmarketplace.application.file.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;

class FileControllerTest extends IntegrationTestBase {

    @Autowired
    private FileStorageService fileStorageService;

    private String storePng(String bucket, String keyPrefix) {
        String key = keyPrefix + "/" + UUID.randomUUID() + ".png";
        fileStorageService.store(bucket, key,
                new ByteArrayInputStream("png-bytes".getBytes(StandardCharsets.UTF_8)),
                9, "image/png");
        return key;
    }

    @Test
    void avatarIsServedToAnonymousWithImageContentType() {
        String key = storePng("avatars", "avatars/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/avatars/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void listingImageIsServedToAnonymous() {
        String key = storePng("images", "listings/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/images/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
    }

    @Test
    void messageAttachmentRequiresAuthentication() {
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        assertThat(getStatus("/files/messages/" + key, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void messageAttachmentIsServedToAuthenticatedUser() {
        var buyer = createUniqueUser(Role.BUYER);
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/messages/" + key, buyer);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
    }

    @Test
    void unknownKeyReturnsNotFound() {
        assertThat(getStatus("/files/avatars/avatars/" + UUID.randomUUID() + "/nope.png", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void traversingKeyIsRejected() {
        // Either StrictHttpFirewall or the service's own path check answers; both are 400.
        assertThat(getStatus("/files/avatars/../../../etc/passwd", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadedAvatarIsDownloadableAtTheReturnedUrl() {
        var buyer = createUniqueUser(Role.BUYER);
        byte[] payload = "real-avatar-bytes".getBytes(StandardCharsets.UTF_8);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", payload, MediaType.IMAGE_PNG).filename("a.png");

        ResponseEntity<String> upload = restClient.post()
                .uri("/users/me/avatar")
                .body(builder.build())
                .headers(authHeaders(buyer))
                .retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);

        String avatarUrl = parseAvatarUrl(upload);
        assertThat(avatarUrl).startsWith("/api/proxy/files/avatars/");

        // The stored URL carries the frontend's proxy prefix; strip it to hit the backend directly.
        String backendPath = avatarUrl.replace("/api/proxy", "");
        ResponseEntity<String> download = getStatus(backendPath, null);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo("real-avatar-bytes");
    }

    private String parseAvatarUrl(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(res.getBody()).get("avatarUrl").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Запустить тесты и убедиться, что они падают**

```bash
gradle test --tests "com.petmarketplace.application.file.controller.FileControllerTest"
```

Ожидается: FAIL. Контроллера ещё нет, поэтому `/files/**` попадает под `anyRequest().authenticated()` — анонимные кейсы получают 401 вместо 200/404, а `uploadedAvatarIsDownloadableAtTheReturnedUrl` падает на проверке префикса или на скачивании.

- [ ] **Step 3: Написать контроллер**

Создать `src/main/java/com/petmarketplace/application/file/controller/FileController.java`:

```java
package com.petmarketplace.application.file.controller;

import com.petmarketplace.infrastructure.storage.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Serves uploaded files")
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "Download a stored file")
    @ApiResponse(responseCode = "200", description = "File returned")
    @ApiResponse(responseCode = "404", description = "File not found")
    @GetMapping("/{bucket}/{*objectKey}")
    public ResponseEntity<Resource> download(@PathVariable String bucket,
                                             @PathVariable String objectKey) {
        // PathPattern's {*var} capture keeps the leading slash; the storage layer wants it bare.
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        InputStream stream = fileStorageService.retrieve(bucket, key);
        MediaType contentType = MediaTypeFactory.getMediaType(key)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                // Object keys embed a UUID, so a stored file never changes under the same URL.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .contentType(contentType)
                .body(new InputStreamResource(stream));
    }
}
```

- [ ] **Step 4: Открыть публичные пути в `SecurityConfig`**

В `src/main/java/com/petmarketplace/infrastructure/security/SecurityConfig.java` вставить две строки сразу после `.requestMatchers("/actuator/health").permitAll()`:

```java
                        .requestMatchers("/actuator/health").permitAll()
                        // Аватары и фотографии объявлений видны анонимам — публичные профили и
                        // каталог открыты. Вложения в переписку требуют аутентификации.
                        .requestMatchers(HttpMethod.GET, "/files/avatars/**", "/files/images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/files/messages/**").authenticated()
                        .requestMatchers("/admin/**").hasAnyRole("ADMIN", "MODERATOR")
```

- [ ] **Step 5: Переименовать бакет фотографий объявлений**

В `src/main/java/com/petmarketplace/application/listing/service/ListingService.java` заменить строку 59:

```java
    private static final String IMAGES_BUCKET = "images";
```

Мигрировать нечего: реальных загрузок не было (хранилище не работало), а демо-сид хранит пути на статику фронтенда `/animals/*.jpg` и этим кодом не затрагивается.

- [ ] **Step 6: Запустить тесты и убедиться, что они проходят**

```bash
gradle test --tests "com.petmarketplace.application.file.controller.FileControllerTest"
```

Ожидается: BUILD SUCCESSFUL, 7 тестов зелёные.

- [ ] **Step 7: Прогнать весь бэкенд-сьют**

```bash
gradle test --rerun-tasks
```

Ожидается: BUILD SUCCESSFUL. Особое внимание — `ListingControllerTest`: смена `IMAGES_BUCKET` не должна ничего сломать, потому что тесты не обращаются к бакету по имени.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vvnovg/pet-marketplace && git add src/main/java/com/petmarketplace/application/file src/main/java/com/petmarketplace/infrastructure/security/SecurityConfig.java src/main/java/com/petmarketplace/application/listing/service/ListingService.java src/test/java/com/petmarketplace/application/file && git commit -m "feat(storage): serve stored files over GET /files and open public buckets"
```

---

### Task 3: Бинарно-безопасный прокси на фронтенде

**Files:**
- Modify: `/Users/vvnovg/pet-marketplace-front/src/lib/api/proxy-handler.ts:70,77`
- Test: `/Users/vvnovg/pet-marketplace-front/src/tests/proxy.test.ts`

**Interfaces:**
- Consumes: `GET /files/{bucket}/{objectKey}` из Task 2 — при обращении через прокси путь выглядит как `/api/proxy/files/...`.
- Produces: `forwardToBackend` возвращает тело ответа побайтово неизменным.

Начать с создания ветки в фронтенд-репозитории:

```bash
cd /Users/vvnovg/pet-marketplace-front && git checkout main && git pull && git checkout -b feat/binary-safe-proxy
```

- [ ] **Step 1: Написать падающий тест**

В `src/tests/proxy.test.ts` дописать в конец блока `describe("forwardToBackend", ...)`:

```ts
  it("returns a binary body byte-for-byte unchanged", async () => {
    // Bytes that are NOT valid UTF-8: decoding them as text replaces each with U+FFFD,
    // which is exactly how an image gets corrupted on the way through the proxy.
    const payload = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0xfe, 0x00, 0x80]);
    server.use(http.get(`${API_BASE}/files/avatars/u1/a.png`, () =>
      HttpResponse.arrayBuffer(payload.buffer as ArrayBuffer, {
        headers: { "content-type": "image/png" },
      }),
    ));

    const req = new Request("http://x/api/proxy/files/avatars/u1/a.png");
    const res = await forwardToBackend(req as unknown as NextRequest, ["files", "avatars", "u1", "a.png"]);

    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("image/png");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(payload);
  });

  it("returns a binary body unchanged after a refresh-retry", async () => {
    const payload = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x90]);
    let first = true;
    server.use(
      http.get(`${API_BASE}/files/messages/u1/a.jpg`, () =>
        first
          ? ((first = false), new HttpResponse(null, { status: 401 }))
          : HttpResponse.arrayBuffer(payload.buffer as ArrayBuffer, {
              headers: { "content-type": "image/jpeg" },
            }),
      ),
      http.post(`${API_BASE}/auth/refresh`, () =>
        HttpResponse.json({ accessToken: "new", refreshToken: "nr", tokenType: "Bearer", expiresIn: 900 }),
      ),
    );

    const req = new Request("http://x/api/proxy/files/messages/u1/a.jpg", {
      headers: { cookie: "pmp_access=stale; pmp_refresh=rr" },
    });
    const res = await forwardToBackend(req as unknown as NextRequest, ["files", "messages", "u1", "a.jpg"]);

    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(payload);
  });
```

Второй кейс существен: `.text()` встречается в файле **дважды**, и путь после обновления токена легко забыть.

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /Users/vvnovg/pet-marketplace-front && pnpm test -- src/tests/proxy.test.ts
```

Ожидается: FAIL — принятые байты не совпадают с отправленными, невалидные для UTF-8 октеты заменены на `0xEF 0xBF 0xBD` (U+FFFD).

- [ ] **Step 3: Сделать прокси бинарно-безопасным**

В `src/lib/api/proxy-handler.ts` заменить обе строки `const body = await upstream.text();` на:

```ts
      // arrayBuffer, а не text: .text() декодирует тело как UTF-8 и разрушает
      // изображения и любые другие бинарные ответы.
      const body = await upstream.arrayBuffer();
```

Первое вхождение — внутри ветки обновления токена, второе — в обычном возврате. Обе обязательны.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
pnpm test -- src/tests/proxy.test.ts
```

Ожидается: PASS, все кейсы файла зелёные, включая ранее существовавшие JSON-кейсы (`NextResponse` одинаково принимает и строку, и `ArrayBuffer`).

- [ ] **Step 5: Прогнать полный фронтенд-гейт**

```bash
pnpm test && pnpm exec tsc --noEmit && pnpm build
```

Ожидается: всё успешно.

- [ ] **Step 6: Коммит**

```bash
git add src/lib/api/proxy-handler.ts src/tests/proxy.test.ts && git commit -m "fix(proxy): forward response bodies as bytes so images survive the hop"
```

---

### Task 4: Сквозная проверка на живом стенде и документация

**Files:**
- Modify: `/Users/vvnovg/pet-marketplace/CLAUDE.md` (раздел File Storage)
- Modify: `/Users/vvnovg/pet-marketplace-front/CLAUDE.md` (раздел про прокси)

**Interfaces:**
- Consumes: всё из задач 1–3.
- Produces: ничего для других задач.

- [ ] **Step 1: Поднять стенд с новым кодом**

```bash
cd /Users/vvnovg/pet-marketplace && docker compose up -d --build app
```

Дождаться готовности:

```bash
curl -fsS http://localhost:8080/api/v1/actuator/health
```

Ожидается: `{"status":"UP",...}`.

- [ ] **Step 2: Проверить загрузку и скачивание вручную**

```bash
SRC=/Users/vvnovg/pet-marketplace-front/public/animals/beagle.jpg
TOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'content-type: application/json' -d '{"email":"buyer@demo.local","password":"Demo12345"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
URL=$(curl -s -X POST http://localhost:8080/api/v1/users/me/avatar -H "authorization: Bearer $TOK" -F "file=@$SRC" | python3 -c 'import sys,json; print(json.load(sys.stdin)["avatarUrl"])')
echo "avatarUrl=$URL"
```

Ожидается: путь вида `/api/proxy/files/avatars/avatars/<uuid>/<uuid>.jpg` — **не** пустая строка.

Затем скачать по нему напрямую с бэкенда (убрав префикс `/api/proxy`) и сверить байты с оригиналом:

```bash
curl -s -o /tmp/downloaded.jpg -w '%{http_code} %{content_type}\n' "http://localhost:8080/api/v1${URL#/api/proxy}"
cmp "$SRC" /tmp/downloaded.jpg && echo "bytes identical"
```

Ожидается: `200 image/jpeg`, затем `bytes identical`.

- [ ] **Step 3: Вернуть демо-данные в исходное состояние**

Загрузка перезаписала аватар демо-покупателя. Сид его не задаёт, поэтому вернуть надо `NULL`:

```bash
docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c "UPDATE users SET avatar_url = NULL WHERE email = 'buyer@demo.local';"
```

Ожидается: `UPDATE 1`.

- [ ] **Step 4: Проверить путь через фронтенд**

```bash
cd /Users/vvnovg/pet-marketplace-front && pnpm exec playwright test --workers=1 --reporter=line
```

Ожидается: 8 passed / 3 skipped / 0 failed — тот же результат, что и до изменений.

- [ ] **Step 5: Обновить документацию бэкенда**

В `/Users/vvnovg/pet-marketplace/CLAUDE.md` заменить раздел `## File Storage` целиком:

```markdown
## File Storage

`FileStorageService` is the abstraction. The active implementation is selected by
`storage.provider` (`local` or `minio`); `local` is the default in every profile.

`LocalFileStorageService` writes to `storage.local.base-path` (default `./uploads`,
`/app/uploads` in the container, backed by the `app-uploads` volume). `store` returns a
**root-relative** URL built from `storage.public-base-path` (default `/api/proxy/files`,
override with `STORAGE_PUBLIC_BASE_PATH`) — it points at the frontend's proxy route,
because the backend is firewall-private in production and the browser only ever talks to
the frontend. Every method normalises `{bucket}/{objectKey}` and refuses paths escaping
the base directory: `objectKey` arrives straight from a request URL in `FileController`.

`FileController` serves `GET /files/{bucket}/{*objectKey}`, deriving Content-Type from the
key's extension. `SecurityConfig` opens `/files/avatars/**` and `/files/images/**` to
anonymous callers (public profiles and catalog) and requires authentication for
`/files/messages/**` (private chat attachments).

`MinioFileStorageService` is NOT implemented: `store`/`getPublicUrl` throw
`UnsupportedOperationException` rather than returning `""`. The empty-string version
caused uploads to answer 200 while storing nothing.

Buckets in use: `avatars` (`ProfileService`), `images` (`ListingService`), `messages`
(`MessageService`). The avatar and message object keys repeat their bucket name
(`avatars/avatars/{userId}/...`) — cosmetic, left alone deliberately.
```

- [ ] **Step 6: Обновить документацию фронтенда**

В `/Users/vvnovg/pet-marketplace-front/CLAUDE.md`, в конце абзаца про `/api/proxy/[...path]` (пункт 1 раздела «Architecture: the security model»), дописать предложение:

```markdown
Response bodies are forwarded with `arrayBuffer()`, never `text()` — the proxy also carries
uploaded images (`/api/proxy/files/**`), and decoding those as UTF-8 corrupts them.
```

- [ ] **Step 7: Коммит документации**

```bash
cd /Users/vvnovg/pet-marketplace && git add CLAUDE.md && git commit -m "docs(claude): document the local storage implementation and file serving"
cd /Users/vvnovg/pet-marketplace-front && git add CLAUDE.md && git commit -m "docs(claude): note that the proxy forwards bodies as bytes"
```

- [ ] **Step 8: Финальный гейт в обоих репозиториях**

Бэкенд:

```bash
cd /Users/vvnovg/pet-marketplace && gradle test --rerun-tasks
```

Фронтенд:

```bash
cd /Users/vvnovg/pet-marketplace-front && pnpm test && pnpm exec tsc --noEmit && pnpm build && pnpm exec playwright test --workers=1
```

Ожидается: обе команды бэкенда и все четыре фронтенда успешны. Ни одну не пропускать: заявлять готовность можно только после того, как вывод каждой увиден.

---

## Порядок выполнения и зависимости

```
Task 1 (сервис) → Task 2 (контроллер + права) ─┐
Task 3 (прокси, независим)  ───────────────────┴→ Task 4 (стенд + документация)
```

Task 3 не зависит от задач 1–2 по коду и может выполняться параллельно, но его ручная проверка на стенде (Task 4) требует уже задеплоенного бэкенда.
