# Спецификация автотестов — Pet Marketplace

Документ описывает полный набор интеграционных автотестов REST API (`/api/v1`)
pet marketplace. Цель — 100% покрытие всех сценариев, включая админские ручки,
кейсы безопасности (401/403), валидации (400), отсутствия ресурса (404),
бизнес-ошибок (409), пагинации и локализации.

Все тесты — интеграционные (RestClient + Testcontainers Postgres/Redis),
расширяют `IntegrationTestBase`. Команда запуска: `gradle test`
(или `gradle test --tests "полное.имя.Класса"`).

---

## 1. Конвенции и фикстуры

### 1.1. Базовый класс

Все тесты наследуются от `com.petmarketplace.IntegrationTestBase`, который:

- Поднимает Testcontainers: PostgreSQL 16 + Redis 7 (`@DynamicPropertySource`).
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`.
- `@Testcontainers(disabledWithoutDocker = true)` — тесты пропускаются без Docker.
- Предоставляет `RestClient` с `baseUrl` = адрес запущённого сервера + `/api/v1`.
- Хелперы: `createUser(Role, suffix)`, `createUniqueUser(Role)` — создают User+Profile
  и возвращают `TestUser(id, email, password, role)`.
- `authHeader(TestUser)` / `authHeaders(TestUser)` — генерируют JWT access-токен
  через `jwtTokenProvider.generateAccessToken(email, role)` и кладут в `Authorization: Bearer …`.

### 1.2. Предзаполненные данные (seed)

Из Liquibase changelog `001-init-schema.yaml`:

- Категория «Собаки»: `DOGS_CATEGORY_ID = 11111111-1111-1111-1111-111111111111`.
- Порода «Лабрадор»: `LABRADOR_BREED_ID = 10000000-0000-0000-0000-000000000000`.
- Есть и другие категории/породы — тесты на локализацию используют их.

### 1.3. Хелпер создания активного листинга

Многие тесты требуют листинг в статусе `ACTIVE`. Используется общий хелпер:

1. Создать seller (`Role.SELLER`), авторизоваться.
2. `POST /listings` с валидным телом → статус `PENDING_MODERATION` (201).
3. Авторизоваться как admin, `PUT /admin/listings/{id}/moderate`
   с `{ "status": "ACTIVE", "reason": null }` → 200.
4. Вернуть `listingId` для дальнейших шагов.

### 1.4. Роли

`Role` enum: `ADMIN`, `MODERATOR`, `SELLER`, `BUYER`. Токен несёт ровно одну роль.

### 1.5. Формат ошибки

Все ошибки возвращают единый envelope `ApiError`:

```json
{
  "timestamp": "2026-07-19T10:00:00Z",
  "status": 409,
  "error": "Business error",
  "message": "текст ошибки",
  "path": "/api/v1/..."
}
```

`spring.jackson.default-property-inclusion: non_null` — null-поля (например `path`,
`details`) отсутствуют в ответе. Таймстемп — UTC ISO. Тесты на ошибки проверяют
`status` и `message` (точное совпадение или contains — см. конкретный кейс),
а также отсутствие лишних полей.

### 1.6. Карта HTTP-кодов

| Ситуация | Код | Исключение |
|---|---|---|
| Бизнес-нарушение (конфликт состояний, статусов) | 409 | `BusinessException` |
| Ошибка валидации DTO / аргументов | 400 | `ValidationException` / `MethodArgumentNotValidException` |
| Ресурс не найден | 404 | `ResourceNotFoundException` |
| Нет токена / токен невалиден | 401 | (security entry point) |
| Токен есть, но не хватает прав | 403 | `AccessDeniedException` (security denied handler) |

> **Важно (частая ошибка в тестах):** в этом проекте есть ДВА разных механизма
> проверки прав, и они дают разные коды:
>
> - **Security-level (`@PreAuthorize` / SecurityConfig path-rules)** → **403**
>   `AccessDeniedException`. Применяется к роли: `POST /listings` (нужен SELLER),
>   `POST /reviews` (нужен BUYER/SELLER), весь `/admin/**` (нужен ADMIN/MODERATOR).
> - **Service-level (проверка владельца/участника в коде сервиса)** → **409**
>   `BusinessException` (например "You are not allowed to modify this listing",
>   "Only the seller can perform this action", "You are not allowed to access this
>   booking", "You are not allowed to manage this subscription"). Применяется к
>   владению сущностью: обновление/удаление чужого листинга, confirm/complete чужого
>   booking, доступ к чужому booking, удаление чужой подписки.
>
> То есть "чужой seller редактирует не свой листинг" → **409**, а не 403.
> А "BUYER создаёт листинг" → **403** (нужна роль SELLER). В таблицах ниже коды
> указаны согласно этому правилу.

---

## 2. Модуль AUTH (`/auth`)

Все ручки публичные (кроме logout, который требует валидного токена в смысле
инвалидации refresh — фактически публичная).

### 2.1. POST `/auth/register`

| ID | Сценарий | Тело | Предусловия | Ожидаемый статус | Проверки |
|---|---|---|---|---|---|
| AUTH-REG-01 | Успешная регистрация | валидный email/пароль | email свободен | 201 | ответ без пароля; в БД User с `role=BUYER`, `verified=false`, `active=true`; создан пустой Profile (rating=0); в Redis токен верификации (TTL 24h) |
| AUTH-REG-02 | Email уже зарегистрирован | существующий email | пользователь есть | 409 | `message` "Email already registered" |
| AUTH-REG-03 | Пустой email | `email: ""` | — | 400 | `details` содержит поле `email` |
| AUTH-REG-04 | Невалидный email | `email: "not-an-email"` | — | 400 | details → `email` |
| AUTH-REG-05 | Пароль < 8 символов | `password: "123"` | — | 400 | details → `password` |
| AUTH-REG-06 | Пароль пустой | `password: ""` | — | 400 | details → `password` |
| AUTH-REG-07 | phone > 20 символов | `phone: "1"*21` | — | 400 | details → `phone` |
| AUTH-REG-08 | firstName > 100 символов | длина 101 | — | 400 | details → `firstName` |
| AUTH-REG-09 | lastName > 100 символов | длина 101 | — | 400 | details → `lastName` |
| AUTH-REG-10 | email > 255 символов | длина 256 | — | 400 | details → `email` |

Постусловие AUTH-REG-01: проверить, что отправлено письмо верификации
(в `test` профиле `mail.enabled=false` → `EmailSenderStub` пишет лог;
проверить через лог/мок, что метод вызван с темой верификации).

### 2.2. POST `/auth/login`

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| AUTH-LOGIN-01 | Успешный вход | верные креды | пользователь `verified=true`, `active=true` | 200 | `TokenResponse`: `accessToken`, `refreshToken`, `tokenType="Bearer"`, `expiresIn` > 0; refresh сохранён в Redis |
| AUTH-LOGIN-02 | Неверный пароль | `password: "wrong"` | пользователь есть | 409 | `message` "Invalid credentials" |
| AUTH-LOGIN-03 | Несуществующий email | рандомный email | — | 409 | тот же код/message что и неверный пароль (без утечки существования) |
| AUTH-LOGIN-04 | Email не подтверждён | верные креды | `verified=false` | 409 | `message` "Email not verified" |
| AUTH-LOGIN-05 | Аккаунт отключён | верные креды | `active=false` | 409 | `message` "Account is disabled" |
| AUTH-LOGIN-06 | Пустой email | `email: ""` | — | 400 | details → `email` |
| AUTH-LOGIN-07 | Пустой пароль | `password: ""` | — | 400 | details → `password` |

Примечание: для AUTH-LOGIN-04 создать пользователя через register и НЕ
подтверждать email. Для AUTH-LOGIN-05 создать, подтвердить, затем через
`/admin/users/{id}/status` деактивировать.

### 2.3. POST `/auth/refresh`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| AUTH-REFRESH-01 | Успешная ротация | валидный refresh из login, user active | 200 | возвращён НОВЫЙ `accessToken`+`refreshToken`; старый refresh инвалидирован в Redis |
| AUTH-REFRESH-02 | Повторное использование старого refresh | тот же токен второй раз | 409 | `message` "Invalid or expired refresh token" |
| AUTH-REFRESH-03 | Несуществующий/фантомный refresh | случайная строка | 409 | "Invalid or expired refresh token" |
| AUTH-REFRESH-04 | Истёкший refresh | токен с истёкшим TTL | 409 | "Invalid or expired refresh token" |
| AUTH-REFRESH-05 | Пустое тело / нет поля | `{}` | 400 | details → `refreshToken` |
| AUTH-REFRESH-06 | Disabled user пытается refresh | user active=false, валидный refresh | 409 | `message` "Account is disabled" |

Проверка ротации: после AUTH-REFRESH-01 повторить запрос со СТАРЫМ токеном →
ожидать 409 (AUTH-REFRESH-02 в той же цепочке).

### 2.4. POST `/auth/logout`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| AUTH-LOGOUT-01 | Успешный logout | валидный refresh | 200 | refresh удалён из Redis; повторный refresh им же → 409 |
| AUTH-LOGOUT-02 | Невалидный refresh | случайная строка | 409 (или 200 без эффекта — см. реализацию) | проверить, что не падает 500 |
| AUTH-LOGOUT-03 | Пустое тело | `{}` | 400 | details → `refreshToken` |

### 2.5. POST `/auth/forgot-password`

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| AUTH-FORGOT-01 | Существующий email | реальный email | — | 200 | отправлено письмо reset; в Redis появился токен |
| AUTH-FORGOT-02 | Несуществующий email | рандомный | — | 200 | **тот же статус**, без утечки существования (защита от перебора) |
| AUTH-FORGOT-03 | Пустой email | `""` | — | 400 | details → `email` |
| AUTH-FORGOT-04 | Невалидный email | `"bad"` | — | 400 | details → `email` |

### 2.6. POST `/auth/reset-password`

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| AUTH-RESET-01 | Успешный сброс | валидный токен + новый пароль | токен из forgot-password | 200 | можно залогиниться с новым паролем; старый пароль не работает |
| AUTH-RESET-02 | Невалидный токен | случайный токен | — | 409 | `message` «Invalid or expired reset token» |
| AUTH-RESET-03 | Истёкший токен | токен с прошедшим TTL | — | 409 | то же message |
| AUTH-RESET-04 | Пустой пароль | `newPassword: ""` | — | 400 | details → `newPassword` |
| AUTH-RESET-05 | Слишком короткий пароль | `"123"` | — | 400 | details → `newPassword` |
| AUTH-RESET-06 | Повторное использование токена | тот же токен дважды | первый раз успех | 409 | токен удалён после первого использования |

### 2.7. POST `/auth/verify-email`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| AUTH-VERIFY-01 | Успешная верификация | валидный токен из письма | 200 | `user.verified = true` в БД; можно login |
| AUTH-VERIFY-02 | Невалидный токен | случайный | 409 | `message` «Invalid or expired verification token» |
| AUTH-VERIFY-03 | Истёкший токен | прошедший TTL | 409 | то же |
| AUTH-VERIFY-04 | Повторное использование токена | тот же токен дважды | 409 | токен удалён |

---

## 3. Модуль USER (`/users`)

### 3.1. GET `/users/me` (auth)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| USER-ME-01 | Без токена | — | 401 | ApiError |
| USER-ME-02 | С валидным токеном | залогинен | 200 | тело содержит `email`, `role`; НЕ содержит `passwordHash` |
| USER-ME-03 | Несуществующий/удалённый user по валидному токену | токен сгенерирован, user удалён | 404 | (или 401 — проверить по реализации) |

### 3.2. PUT `/users/me` (auth) — `ProfileUpdateRequest`

Поля: `bio` (≤2000), `country`/`city` (≤100), `address` (≤255),
`lat`/`longitude` (`@DecimalMin/Max`).

| ID | Сценарий | Тело | Статус | Проверки |
|---|---|---|---|---|
| USER-PUT-01 | Успешное обновление | валидное тело | 200 | ответ содержит обновлённые поля |
| USER-PUT-02 | Без токена | — | 401 | — |
| USER-PUT-03 | bio > 2000 символов | длина 2001 | 400 | details → `bio` |
| USER-PUT-04 | country > 100 | длина 101 | 400 | details → `country` |
| USER-PUT-05 | city > 100 | длина 101 | 400 | details → `city` |
| USER-PUT-06 | address > 255 | длина 256 | 400 | details → `address` |
| USER-PUT-07 | latitude вне диапазона | `latitude: -91` | 400 | details → `latitude` |
| USER-PUT-08 | longitude вне диапазона | `longitude: 181` | 400 | details → `longitude` |
| USER-PUT-09 | Пустое тело | `{}` | 200 | все поля null/опускаются (все опциональны) |

### 3.3. POST `/users/me/avatar` (auth, multipart)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| USER-AVATAR-01 | Успешная загрузка | валидный PNG/JPG | 200 | ответ содержит URL аватара |
| USER-AVATAR-02 | Без токена | — | 401 | — |
| USER-AVATAR-03 | Невалидный файл | пустой/не-изображение | 400 | — |

### 3.4. GET `/users/{id}` (public)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| USER-GET-01 | Существующий id | — | 200 | публичные поля (firstName, rating, totalReviews); нет email/чувствительного |
| USER-GET-02 | Несуществующий id | случайный UUID | 404 | — |
| USER-GET-03 | Без токена (публичная ручка) | — | 200 | должна работать без auth |

### 3.5. GET `/users/{id}/listings` (public)

> **Внимание:** `UserService.listUserListings` — TODO-заглушка, возвращает
> `Page.empty()`. Все кейсы ниже ожидают пустой результат, пока метод не
> реализован; после реализации — обновить ожидания на реальные листинги.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| USER-LIST-01 | Есть активные листинги | user-продавец с ACTIVE листингами | 200 | **пустая страница** (заглушка); TODO после реализации |
| USER-LIST-02 | Нет листингов | новый user | 200 | пустой массив |
| USER-LIST-03 | Несуществующий user | случайный UUID | 200 (заглушка) | TODO: должно быть 404 после реализации |
| USER-LIST-04 | Без токена | — | 200 | публичная |

### 3.6. GET `/users/{id}/reviews` (требует auth — НЕ в permitAll)

Правило видимости: если запрашивающий — сам получатель отзывов ИЛИ
ADMIN/MODERATOR → возвращаются ВСЕ отзывы (включая PENDING/REJECTED);
иначе только APPROVED.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| USER-REV-01 | Есть одобренные отзывы (чужой просит) | есть APPROVED, запрашивает другой user | 200 | массив APPROVED отзывов |
| USER-REV-02 | Нет отзывов | — | 200 | пустой массив |
| USER-REV-03 | Несуществующий user | случайный UUID | 200 (пусто) | делегировано ReviewService, не падает 404 |
| USER-REV-04 | Чужой не видит PENDING/REJECTED | есть pending review, запрашивает посторонний | 200 | массив НЕ содержит pending/rejected |
| USER-REV-05 | Сам получатель видит PENDING | seller запрашивает свои отзывы, есть PENDING | 200 | массив СОДЕРЖИТ pending |
| USER-REV-06 | ADMIN/MODERATOR видит все | admin, есть PENDING | 200 | массив содержит pending |
| USER-REV-07 | Без токена | — | 401 | ручка требует auth |

---

## 4. Модуль CATEGORY (`/categories`) — публичный

### 4.1. GET `/categories`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| CAT-GET-01 | Получить все категории | seed загружен | 200 | массив содержит «Собаки» и др. |
| CAT-GET-02 | Без токена | — | 200 | публичная |
| CAT-GET-03 | Локализация ru | `Accept-Language: ru` | 200 | имена на русском |
| CAT-GET-04 | Локализация en | `Accept-Language: en` | 200 | имена на английском |
| CAT-GET-05 | Кеширование | повторный запрос | 200 | второй запрос берёт из Redis-кеша (проверить через логи/время) |

### 4.2. GET `/categories/{id}/breeds`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| CAT-BREED-01 | Существующая категория | DOGS_CATEGORY_ID | 200 | содержит «Лабрадор» |
| CAT-BREED-02 | Несуществующая категория | случайный UUID | 404 | — |
| CAT-BREED-03 | Локализация ru/en | Accept-Language | 200 | имена пород на нужном языке |
| CAT-BREED-04 | Без токена | — | 200 | публичная |

---

## 5. Модуль LISTING (`/listings`)

### 5.1. GET `/listings` (public, поиск с фильтрами)

`ListingSearchRequest`: `categoryId`, `breedId`, `city`, `minPrice`, `maxPrice`,
`gender`, `minAge`, `maxAge` + `Pageable` (`page`, `size`, `sort`).

| ID | Сценарий | Параметры | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| LST-SEARCH-01 | Без фильтров | — | есть ACTIVE листинги | 200 | page с контентом |
| LST-SEARCH-02 | Без токена | — | — | 200 | публичная |
| LST-SEARCH-03 | Фильтр по категории | `categoryId=DOGS` | есть собаки | 200 | только собаки |
| LST-SEARCH-04 | Фильтр по породе | `breedId=LABRADOR` | — | 200 | только лабрадоры |
| LST-SEARCH-05 | Фильтр по городу (case-insensitive, trim) | `city=Moscow` (и `"  moscow "`) | листинги в Moscow | 200 | совпадают по lower-case trimmed |
| LST-SEARCH-06 | Цена between | minPrice, maxPrice | — | 200 | диапазон |
| LST-SEARCH-07 | Только minPrice | minPrice | — | 200 | `>= minPrice` |
| LST-SEARCH-08 | Только maxPrice | maxPrice | — | 200 | `<= maxPrice` |
| LST-SEARCH-09 | Фильтр по полу | `gender=MALE` | — | 200 | только MALE |
| LST-SEARCH-10 | Возраст between | minAge, maxAge | — | 200 | диапазон ageMonths |
| LST-SEARCH-11 | Только minAge / только maxAge | один параметр | — | 200 | соответствующая граница |
| LST-SEARCH-12 | Комбинация всех фильтров | все заданы | — | 200 | пересечение условий |
| LST-SEARCH-13 | Все фильтры null | пустой запрос | — | 200 | все ACTIVE листинги (без NPE — регресс на `Spec.and(null)`) |
| LST-SEARCH-14 | Пагинация | `page=0&size=2` | >2 листинга | 200 | `content.size()<=2`, `totalElements>2` |
| LST-SEARCH-15 | Пустая страница | `page=999` | — | 200 | `content: []` |
| LST-SEARCH-16 | Сортировка | `sort=price,asc` | — | 200 | цены по возрастанию |
| LST-SEARCH-17 | Несуществующая категория в фильтре | случайный categoryId | — | 200 | пустой результат |

**Важно:** LST-SEARCH-13 — регрессионный тест на баг Spring Data 4
`Specification.and(null)` → теперь `Specification.allOf` с фильтром null.

### 5.2. GET `/listings/{id}` (public)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-GET-01 | Существующий ACTIVE листинг | — | 200 | полное тело листинга |
| LST-GET-02 | Несуществующий id | случайный UUID | 404 | — |
| LST-GET-03 | Локализация category/breed | `Accept-Language: en` | 200 | имена на en |
| LST-GET-04 | Без токена | — | 200 | публичная |

### 5.3. POST `/listings` (hasRole SELLER) — `ListingCreateRequest`

Валидация: `categoryId` `@NotNull`, `breedId` `@NotNull`, `title` `@NotBlank @Size(max=255)`,
`description` `@Size(max=4000)`, `price` `@NotNull @Positive`, `currency` `@NotBlank @Size(min=3,max=3)`,
`gender` `@NotNull`, `ageMonths` `@NotNull @Min(0)`, `color` `@Size(max=100)`, `weightKg` `@Positive`,
`healthInfo` `@Size(max=2000)`, `locationCountry`/`locationCity` `@Size(max=100)`.

| ID | Сценарий | Тело/Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-CREATE-01 | Успешное создание | seller, валидное тело | 201 | статус `PENDING_MODERATION`; id не null |
| LST-CREATE-02 | Без токена | — | 401 | — |
| LST-CREATE-03 | Роль BUYER | buyer токен | 403 | недостаточно прав (нужен SELLER) |
| LST-CREATE-04 | Роль ADMIN | admin токен | 403 (или 201 — проверить; admin не SELLER) | — |
| LST-CREATE-05 | categoryId null | `categoryId: null` | 400 | details → `categoryId` |
| LST-CREATE-06 | breedId null | `breedId: null` | 400 | details → `breedId` |
| LST-CREATE-07 | title пустой | `title: ""` | 400 | details → `title` |
| LST-CREATE-08 | title > 255 | длина 256 | 400 | details → `title` |
| LST-CREATE-09 | description > 4000 | длина 4001 | 400 | details → `description` |
| LST-CREATE-10 | price null | `price: null` | 400 | details → `price` |
| LST-CREATE-11 | price <= 0 | `price: 0` и `-1` | 400 | details → `price` |
| LST-CREATE-12 | currency != 3 символа | `currency: "US"` и `"USDD"` | 400 | details → `currency` |
| LST-CREATE-13 | currency пустой | `""` | 400 | details → `currency` |
| LST-CREATE-14 | gender null | — | 400 | details → `gender` |
| LST-CREATE-15 | ageMonths null | — | 400 | details → `ageMonths` |
| LST-CREATE-16 | ageMonths < 0 | `ageMonths: -1` | 400 | details → `ageMonths` |
| LST-CREATE-17 | color > 100 | длина 101 | 400 | details → `color` |
| LST-CREATE-18 | weightKg <= 0 | `weightKg: 0` | 400 | details → `weightKg` |
| LST-CREATE-19 | healthInfo > 2000 | длина 2001 | 400 | details → `healthInfo` |
| LST-CREATE-20 | locationCountry > 100 | длина 101 | 400 | details → `locationCountry` |
| LST-CREATE-21 | locationCity > 100 | длина 101 | 400 | details → `locationCity` |
| LST-CREATE-22 | Категория не найдена | случайный categoryId | 404 | — |
| LST-CREATE-23 | Порода не найдена | случайный breedId | 404 | — |

### 5.4. PUT `/listings/{id}` (seller-владелец или admin)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-UPDATE-01 | Владелец обновляет | seller-автор | 200 | поля изменились |
| LST-UPDATE-02 | Admin обновляет чужой | admin токен | 200 | — |
| LST-UPDATE-03 | Чужой seller | не-владелец seller | 409 | `BusinessException` "You are not allowed to modify this listing" (проверка в сервисе, НЕ @PreAuthorize) |
| LST-UPDATE-04 | Без токена | — | 401 | — |
| LST-UPDATE-05 | Несуществующий id | случайный UUID | 404 | — |
| LST-UPDATE-06 | Невалидное тело | title > 255 | 400 | details → `title` |
| LST-UPDATE-07 | Изменить categoryId на несуществующий | случайный categoryId | 404 | "Category not found" |
| LST-UPDATE-08 | Изменить breedId на несуществующий | случайный breedId | 404 | "Breed not found" |
| LST-UPDATE-09 | breedId = null (очистить породу) | был breed | 200 | breed очищен |

### 5.5. DELETE `/listings/{id}` (seller-владелец или admin) → 204

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-DEL-01 | Владелец удаляет | seller-автор | 204 | повторный GET → 404; связанные изображения удалены |
| LST-DEL-02 | Admin удаляет чужой | admin | 204 | — |
| LST-DEL-03 | Чужой seller | не-владелец | 409 | `BusinessException` "You are not allowed to modify this listing" |
| LST-DEL-04 | Без токена | — | 401 | — |
| LST-DEL-05 | Несуществующий id | случайный UUID | 404 | — |

### 5.6. POST `/listings/{id}/images` (multipart) → 201

Валидация: content-type `image/*`, ≤5 MB, не более 10 изображений на листинг
(`MAX_IMAGES_PER_LISTING`); первое изображение получает `isMain=true`.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-IMG-01 | Загрузка изображения | владелец, валидный файл | 201 | ответ с imageId/url |
| LST-IMG-02 | Чужой seller | не-владелец | 409 | `BusinessException` "You are not allowed to modify this listing" |
| LST-IMG-03 | Без токена | — | 401 | — |
| LST-IMG-04 | Несуществующий листинг | случайный id | 404 | — |
| LST-IMG-05 | Невалидный файл | не image/* | 400 | `ValidationException` "Uploaded file must be an image" |
| LST-IMG-06 | Файл > 5 MB | большой файл | 400 | "Image must not exceed 5 MB" |
| LST-IMG-07 | Пустой файл | file пустой | 400 | "Image file is required" |
| LST-IMG-08 | > 10 изображений на листинг | уже 10 | 400 | "Maximum 10 images per listing allowed" |
| LST-IMG-09 | Первое изображение = main | пока 0 изображений | 201 | `isMain=true` у нового |

### 5.7. DELETE `/listings/{id}/images/{imageId}` → 204

При удалении main-изображения оставшееся с наименьшим `orderIndex` становится main.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-IMGDEL-01 | Удаление изображения | владелец | 204 | — |
| LST-IMGDEL-02 | Чужой seller | не-владелец | 409 | `BusinessException` "You are not allowed to modify this listing" |
| LST-IMGDEL-03 | Несуществующее изображение | случайный imageId | 404 | "Image not found" |
| LST-IMGDEL-04 | Без токена | — | 401 | — |
| LST-IMGDEL-05 | Удаление main → продвижение | удаляем main, есть другие | 204 | другое стало main |

### 5.8. POST `/listings/{id}/favorite` → 200; DELETE `/listings/{id}/favorite` → 204

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-FAV-01 | Добавить в избранное | залогинен, ACTIVE листинг | 200 | появляется в `/favorites` |
| LST-FAV-02 | Повторное добавление (идемпотентность) | уже в избранном | 200 | не дублируется в `/favorites` |
| LST-FAV-03 | Удалить из избранного | был в избранном | 204 | пропадает из `/favorites` |
| LST-FAV-04 | Удалить не из избранного | не добавлял | 204 | идемпотентный DELETE; 404 только при несуществующем листинге (см. LST-FAV-06) |
| LST-FAV-05 | Без токена | — | 401 | — |
| LST-FAV-06 | Несуществующий листинг | случайный id | 404 | — |

### 5.9. POST `/listings/{id}/book` → 201

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-BOOK-01 | Успешный заказ | buyer, ACTIVE листинг | 201 | `BookingResponse` со статусом PENDING |
| LST-BOOK-02 | Листинг не ACTIVE | листинг в PENDING_MODERATION/SOLD/RESERVED | 409 | `BusinessException` "Booking is only available for active listings" |
| LST-BOOK-03 | Уже есть активный заказ | есть PENDING/CONFIRMED того же buyer на листинг | 409 | "Active booking already exists for this listing" |
| LST-BOOK-04 | Seller заказывает свой листинг | seller = owner листинга | 409 | "Buyer cannot book their own listing" |
| LST-BOOK-05 | Без токена | — | 401 | — |
| LST-BOOK-06 | Несуществующий листинг | случайный id | 404 | "Listing not found" |
| LST-BOOK-07 | С сообщением | `?message=hello` | 201 | сообщение сохранено |

### 5.10. PUT `/listings/{id}/status` (владелец или admin) — `ListingStatusUpdateRequest`

`@NotNull ListingStatus status`. Без guard переходов — можно установить любой
`ListingStatus` напрямую. Проверка прав через `canModify` (владелец/ADMIN/MODERATOR).

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| LST-STATUS-01 | Владелец меняет статус | seller-владелец | 200 | статус изменился |
| LST-STATUS-02 | Admin меняет статус | admin | 200 | — |
| LST-STATUS-03 | Чужой seller | не-владелец, не admin/mod | 409 | `BusinessException` "You are not allowed to change this listing status" |
| LST-STATUS-04 | Без токена | — | 401 | — |
| LST-STATUS-05 | Несуществующий id | случайный UUID | 404 | — |
| LST-STATUS-06 | status null | `status: null` | 400 | details → `status` |
| LST-STATUS-07 | Невалидный enum | `status: "FOO"` | 400 | — |
| LST-STATUS-08 | Любой статус без guard | SOLD→ACTIVE напрямую | 200 | переход НЕ защищён (особенность реализации) |

---

## 6. Модуль BOOKING (`/bookings`, @PreAuthorize isAuthenticated)

### 6.1. GET `/bookings`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| BK-GET-01 | Свои бронирования | есть bookings у user | 200 | массив только своих bookings |
| BK-GET-02 | Без токена | — | 401 | — |
| BK-GET-03 | Пустой список | новых user нет bookings | 200 | `[]` |
| BK-GET-04 | Пагинация | `page&size` | 200 | корректные meta |

### 6.2. GET `/bookings/{id}`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| BK-GETID-01 | Свой booking (как buyer или seller) | — | 200 | тело booking |
| BK-GETID-02 | Чужой booking | не участник | 409 | `BusinessException` "You are not allowed to access this booking" (проверка в сервисе, НЕ security 403) |
| BK-GETID-03 | Несуществующий id | случайный UUID | 404 | — |
| BK-GETID-04 | Без токена | — | 401 | — |

### 6.3. PUT `/bookings/{id}/confirm` (только seller)

Переход PENDING → CONFIRMED; листинг → RESERVED. Seller определяется по листингу.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| BK-CONFIRM-01 | Seller подтверждает | booking PENDING, текущий = seller листинга | 200 | статус CONFIRMED; листинг RESERVED |
| BK-CONFIRM-02 | Buyer подтверждает | buyer токен | 409 | `BusinessException` "Only the seller can perform this action" |
| BK-CONFIRM-03 | Чужой seller | не seller этого листинга | 409 | "Only the seller can perform this action" |
| BK-CONFIRM-04 | Уже CONFIRMED | booking CONFIRMED | 409 | "Only pending bookings can be confirmed" |
| BK-CONFIRM-05 | Уже CANCELLED | booking CANCELLED | 409 | "Only pending bookings can be confirmed" |
| BK-CONFIRM-06 | Уже COMPLETED | booking COMPLETED | 409 | "Only pending bookings can be confirmed" |
| BK-CONFIRM-07 | Несуществующий id | случайный UUID | 404 | — |
| BK-CONFIRM-08 | Без токена | — | 401 | — |

### 6.4. PUT `/bookings/{id}/cancel`

Нельзя отменить уже CANCELLED/COMPLETED; CONFIRMED→отмена возвращает листинг в ACTIVE.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| BK-CANCEL-01 | Buyer отменяет PENDING | buyer, booking PENDING | 200 | статус CANCELLED; листинг остался ACTIVE |
| BK-CANCEL-02 | Seller отменяет CONFIRMED | seller, booking CONFIRMED | 200 | статус CANCELLED; листинг → ACTIVE |
| BK-CANCEL-03 | Buyer отменяет CONFIRMED | buyer, booking CONFIRMED | 200 | листинг → ACTIVE |
| BK-CANCEL-04 | Уже CANCELLED | booking CANCELLED | 409 | "Booking is already cancelled" |
| BK-CANCEL-05 | Уже COMPLETED | booking COMPLETED | 409 | "Completed bookings cannot be cancelled" |
| BK-CANCEL-06 | Чужой booking | не участник | 409 | "You are not allowed to access this booking" |
| BK-CANCEL-07 | Несуществующий id | случайный UUID | 404 | — |
| BK-CANCEL-08 | Без токена | — | 401 | — |

### 6.5. PUT `/bookings/{id}/complete` (только seller)

Переход CONFIRMED → COMPLETED; листинг → SOLD. Открывает возможность оставить отзыв.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| BK-COMPLETE-01 | Seller завершает | booking CONFIRMED, текущий = seller | 200 | статус COMPLETED; листинг SOLD |
| BK-COMPLETE-02 | Buyer завершает | buyer токен | 409 | `BusinessException` "Only the seller can perform this action" |
| BK-COMPLETE-03 | Чужой seller | не seller | 409 | "Only the seller can perform this action" |
| BK-COMPLETE-04 | Из PENDING (без confirm) | booking PENDING | 409 | "Only confirmed bookings can be completed" |
| BK-COMPLETE-05 | Уже COMPLETED | booking COMPLETED | 409 | "Only confirmed bookings can be completed" |
| BK-COMPLETE-06 | Уже CANCELLED | booking CANCELLED | 409 | "Only confirmed bookings can be completed" |
| BK-COMPLETE-07 | Несуществующий id | случайный UUID | 404 | — |
| BK-COMPLETE-08 | Без токена | — | 401 | — |

---

## 7. Модуль MESSAGE (`/messages`, isAuthenticated)

### 7.1. GET `/messages` (список диалогов)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| MSG-CONV-01 | Есть диалоги | есть сообщения с другим user | 200 | массив диалогов (последнее сообщение каждого) |
| MSG-CONV-02 | Нет диалогов | новый user | 200 | `[]` |
| MSG-CONV-03 | Без токена | — | 401 | — |
| MSG-CONV-04 | Непрочитанные считаются | есть непрочитанные | 200 | в ответе счётчик unread > 0 |

### 7.2. GET `/messages/{userId}` (история с конкретным пользователем)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| MSG-HIST-01 | История с пользователем | есть переписка | 200 | сообщения в порядке desc |
| MSG-HIST-02 | Нет переписки | никогда не писали | 200 | `[]` |
| MSG-HIST-03 | С самим собой | userId = свой id | 409 | `BusinessException` "You cannot view a conversation with yourself" |
| MSG-HIST-04 | Без токена | — | 401 | — |
| MSG-HIST-05 | Пагинация | `page&size` | 200 | корректные meta |

### 7.3. POST `/messages` (multipart) — `MessageSendRequest`

`@NotNull UUID receiverId`, опц. `UUID listingId`, `@Size(max=2000) content`
(текст в поле `content`, НЕ `text`). Нельзя отправить себе; должно быть ИЛИ
content, ИЛИ attachment (`@RequestPart("attachment")`, image/*, ≤5 MB).
Вложение хранится в бакете `messages`. Ошибки уведомлений логируются, не пробрасываются.

| ID | Сценарий | Тело/Условия | Статус | Проверки |
|---|---|---|---|---|
| MSG-SEND-01 | Успешная отправка текста | receiverId + content | 200 | сообщение создано, видно в истории |
| MSG-SEND-02 | Успешная отправка с вложением | receiverId + файл, без content | 200 | attachment сохранён (url не null) |
| MSG-SEND-03 | receiverId null | `receiverId: null` | 400 | details → `receiverId` |
| MSG-SEND-04 | Отправка самому себе | receiverId = свой id | 409 | `BusinessException` "You cannot send a message to yourself" |
| MSG-SEND-05 | Нет ни content, ни вложения | receiverId only | 400 | `ValidationException` "Message must contain text or an attachment" |
| MSG-SEND-06 | content > 2000 | длина 2001 | 400 | details → `content` |
| MSG-SEND-07 | Несуществующий receiver | случайный UUID | 404 | "Receiver not found" |
| MSG-SEND-08 | Несуществующий listingId | `listingId: случайный` | 404 | "Listing not found" |
| MSG-SEND-09 | Вложение не-изображение | text/csv файл | 400 | "Attachment must be an image" |
| MSG-SEND-10 | Вложение > 5 MB | большой файл | 400 | "Attachment must not exceed 5 MB" |
| MSG-SEND-11 | Без токена | — | 401 | — |

### 7.4. PUT `/messages/{id}/read` → 200

Загружает по `id` И `receiverId = текущий`; иначе 404 (т.е. sender/посторонний → 404, не 403).

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| MSG-READ-01 | Отметить прочитанным | получатель, unread | 200 | сообщение `read=true` |
| MSG-READ-02 | Не получатель (sender) | sender пытается | 404 | "Message not found" (запрос по id+receiverId) |
| MSG-READ-03 | Уже прочитано | `read=true` | 200 | идемпотентно |
| MSG-READ-04 | Несуществующий id | случайный UUID | 404 | — |
| MSG-READ-05 | Без токена | — | 401 | — |

---

## 8. Модуль REVIEW (`/reviews`, isAuthenticated; create — hasRole BUYER,SELLER)

### 8.1. POST `/reviews` — `ReviewCreateRequest`

`bookingId` `@NotNull`, `rating` `@Min(1) @Max(5)`, `comment` `@Size(max=2000)`.

Бизнес-правила: отзыв может оставить ТОЛЬКО buyer этого booking; booking должен быть
COMPLETED; нельзя оставить повторный отзыв на тот же booking; статус нового отзыва PENDING.

| ID | Сценарий | Тело/Условия | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| REV-CREATE-01 | Успешное создание | валидное, buyer этого booking | booking COMPLETED | 200 | статус `PENDING` |
| REV-CREATE-02 | bookingId null | `bookingId: null` | — | 400 | details → `bookingId` |
| REV-CREATE-03 | rating < 1 | `rating: 0` | — | 400 | `ValidationException` "Rating must be between 1 and 5" (и/или details) |
| REV-CREATE-04 | rating > 5 | `rating: 6` | — | 400 | то же |
| REV-CREATE-05 | comment > 2000 | длина 2001 | — | 400 | details → `comment` |
| REV-CREATE-06 | Не buyer этого booking | чужой BUYER, не участник | — | 409 | `BusinessException` "Only the buyer can leave a review for this booking" |
| REV-CREATE-07 | Booking не COMPLETED | booking PENDING/CONFIRMED | — | 409 | "Review can only be created for completed bookings" |
| REV-CREATE-08 | Повторный отзыв | уже оставлен на этот booking | — | 409 | "Review already exists for this booking" |
| REV-CREATE-09 | Несуществующий booking | случайный bookingId | — | 404 | "Booking not found" |
| REV-CREATE-10 | Без токена | — | — | 401 | — |
| REV-CREATE-11 | Продавец этого booking пытается | seller листинга | — | 409 | "Only the buyer can leave a review" (SELLER-роль допускается к ручке, но не к этому booking) |
| REV-CREATE-12 | Пользователь без BUYER/SELLER роли | ADMIN токен | — | 403 | @PreAuthorize hasAnyRole('BUYER','SELLER') на методе |

### 8.2. GET `/reviews/{userId}` (требует auth)

Видимость: запрашивающий-получатель или ADMIN/MODERATOR → все отзывы; иначе только APPROVED.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| REV-GET-01 | Есть одобренные отзывы (чужой) | есть APPROVED, запрашивает посторонний | 200 | массив APPROVED |
| REV-GET-02 | Нет отзывов | — | 200 | `[]` |
| REV-GET-03 | Чужой не видит PENDING/REJECTED | есть pending/rejected | 200 | массив их не содержит |
| REV-GET-04 | Несуществующий user | случайный UUID | 200 | пусто (делегировано сервису) |
| REV-GET-05 | Сам получатель видит PENDING | seller запрашивает свои, есть PENDING | 200 | массив содержит pending |
| REV-GET-06 | ADMIN/MODERATOR видит все | admin, есть PENDING | 200 | массив содержит pending |
| REV-GET-07 | Без токена | — | 401 | ручка требует auth |

---

## 9. Модуль FAVORITE (`/favorites`, isAuthenticated)

### 9.1. GET `/favorites`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| FAV-GET-01 | Есть избранное | добавлял листинги | 200 | массив избранных |
| FAV-GET-02 | Пустое | ничего не добавлял | 200 | `[]` |
| FAV-GET-03 | Без токена | — | 401 | — |

### 9.2. POST `/favorites/{listingId}` → 201 (идемпотент)

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| FAV-ADD-01 | Добавить | залогинен, ACTIVE листинг | 201 | появляется в GET |
| FAV-ADD-02 | Повторное добавление (идемпотент) | уже добавлен | 201 (или 200 — проверить) | не дублируется |
| FAV-ADD-03 | Несуществующий листинг | случайный id | 404 | — |
| FAV-ADD-04 | Без токена | — | 401 | — |

### 9.3. DELETE `/favorites/{listingId}` → 204

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| FAV-DEL-01 | Удалить | был в избранном | 204 | пропадает из GET |
| FAV-DEL-02 | Удалить не из избранного | не добавлял | 204 | идемпотентный DELETE; 404 только при несуществующем листинге (FAV-DEL-04) |
| FAV-DEL-03 | Без токена | — | 401 | — |
| FAV-DEL-04 | Несуществующий листинг | случайный id | 404 | — |

---

## 10. Модуль SUBSCRIPTION (`/subscriptions`, isAuthenticated)

### 10.1. GET `/subscriptions`

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| SUB-GET-01 | Есть подписки | создавал | 200 | массив подписок (JSONB-фильтры) |
| SUB-GET-02 | Пусто | не создавал | 200 | `[]` |
| SUB-GET-03 | Без токена | — | 401 | — |

### 10.2. POST `/subscriptions` — `SubscriptionCreateRequest`

Поля: `categoryId`, `breedId`, `city`, `minPrice`/`maxPrice` (`@PositiveOrZero`),
`gender`, `minAge`/`maxAge`, `hasVaccination`, `hasDocuments`.

| ID | Сценарий | Тело | Статус | Проверки |
|---|---|---|---|---|
| SUB-CREATE-01 | Успешное создание | валидное | 201 | id не null, фильтры сохранены, `active=true` |
| SUB-CREATE-02 | minPrice < 0 | `minPrice: -1` | 400 | details → `minPrice` |
| SUB-CREATE-03 | maxPrice < 0 | `maxPrice: -1` | 400 | details → `maxPrice` |
| SUB-CREATE-04 | Без токена | — | 401 | — |
| SUB-CREATE-05 | Пустое тело (все опциональны) | `{}` | 201 | подписка без фильтров |

### 10.3. DELETE `/subscriptions/{id}` → 204 (только своя подписка)

Загружает подписку (404 если нет), затем проверяет `owner == currentUser`
(иначе `BusinessException` 409).

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| SUB-DEL-01 | Удалить свою | владелец | 204 | пропадает из GET |
| SUB-DEL-02 | Чужая подписка | чужой id | 409 | `BusinessException` "You are not allowed to manage this subscription" |
| SUB-DEL-03 | Несуществующий id | случайный UUID | 404 | "Subscription not found" |
| SUB-DEL-04 | Без токена | — | 401 | — |

---

## 11. Модуль ADMIN (`/admin`, @PreAuthorize hasAnyRole ADMIN,MODERATOR) — детально

Все ручки требуют роль ADMIN или MODERATOR. Тестовая матрица для КАЖДОЙ админ-ручки
включает: ADMIN→200, MODERATOR→200, BUYER/SELLER→403, без токена→401.

### 11.1. Доступ (сквозной набор для каждой ручки)

Для каждой ручки ниже выполнить матрицу доступа:

| Ручка | ADMIN | MODERATOR | SELLER/BUYER | Без токена |
|---|---|---|---|---|
| GET `/admin/users` | 200 | 200 | 403 | 401 |
| PUT `/admin/users/{id}/status` | 200 | 200 | 403 | 401 |
| PUT `/admin/users/{id}/role` | 200 | 200 | 403 | 401 |
| GET `/admin/listings/pending` | 200 | 200 | 403 | 401 |
| PUT `/admin/listings/{id}/moderate` | 200 | 200 | 403 | 401 |
| GET `/admin/reviews/pending` | 200 | 200 | 403 | 401 |
| PUT `/admin/reviews/{id}/moderate` | 200 | 200 | 403 | 401 |
| GET `/admin/statistics` | 200 | 200 | 403 | 401 |

> Кейсы доступа обозначаются `ADMIN-ACCESS-<ручка>-<роль>`, всего 8×4 = 32 кейса.

### 11.2. GET `/admin/users` (фильтры + пагинация)

Параметры: `role`, `active`, `verified`, `search`, `page`, `size`, `sort`.

| ID | Сценарий | Параметры | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| ADMIN-USERS-01 | Без фильтров | — | есть пользователи | 200 | page со всеми |
| ADMIN-USERS-02 | Фильтр по role | `role=SELLER` | есть sellers | 200 | все записи `role=SELLER` |
| ADMIN-USERS-03 | Фильтр по active | `active=false` | есть деактивированные | 200 | все `active=false` |
| ADMIN-USERS-04 | Фильтр по verified | `verified=false` | есть неподтверждённые | 200 | все `verified=false` |
| ADMIN-USERS-05 | Поиск по строке | `search=ivan` | есть «Ivan» | 200 | содержит совпадения |
| ADMIN-USERS-06 | Комбинация фильтров | `role=BUYER&active=true` | — | 200 | пересечение |
| ADMIN-USERS-07 | Пагинация | `page=0&size=2` | >2 users | 200 | `content.size<=2`, `totalElements>2` |
| ADMIN-USERS-08 | Пустой результат | `search=zzznonexistent` | — | 200 | `content: []` |
| ADMIN-USERS-09 | Сортировка | `sort=createdAt,desc` | — | 200 | по убыванию |
| ADMIN-USERS-10 | Тело ответа не содержит passwordHash | — | — | 200 | проверка отсутствия поля |

### 11.3. PUT `/admin/users/{id}/status` — `UserStatusUpdateRequest`

`active` `@NotNull` (Boolean), `reason` (String). Переключает флаг `active`;
логирует изменение (старое→новое) с причиной.

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| ADMIN-STATUS-01 | Активировать | `active:true, reason:null` | user active=false | 200 | user.active=true |
| ADMIN-STATUS-02 | Деактивировать | `active:false, reason:"spam"` | active=true | 200 | user.active=false; login этого user возвращает 409 "Account is disabled" |
| ADMIN-STATUS-03 | active null | `active:null` | — | 400 | details → `active` |
| ADMIN-STATUS-04 | Несуществующий user | случайный UUID | — | 404 | "User not found" |
| ADMIN-STATUS-05 | Деактивировать уже деактивированного | `active:false` | active=false | 200 | идемпотентно (без guard) |
| ADMIN-STATUS-06 | Связка с auth | после деактивации | user пытается login/refresh | 409 | "Account is disabled" |

### 11.4. PUT `/admin/users/{id}/role` — `UserRoleUpdateRequest`

`role` `@NotNull` (Role enum). Назначает любую роль; БЕЗ guard на понижение
последнего админа или само-понижение (отдельные тесты на это не требуются,
но поведение задокументировано).

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| ADMIN-ROLE-01 | Повысить до ADMIN | `role:ADMIN` | user BUYER | 200 | user.role=ADMIN |
| ADMIN-ROLE-02 | BUYER→SELLER | `role:SELLER` | — | 200 | user.role=SELLER |
| ADMIN-ROLE-03 | Назначить MODERATOR | `role:MODERATOR` | — | 200 | user.role=MODERATOR |
| ADMIN-ROLE-04 | role null | `role:null` | — | 400 | details → `role` |
| ADMIN-ROLE-05 | Невалидный role | `role:"SUPERUSER"` | — | 400 | details → `role` |
| ADMIN-ROLE-06 | Несуществующий user | случайный UUID | — | 404 | "User not found" |
| ADMIN-ROLE-07 | Та же роль (идемпотент) | `role:BUYER` | уже BUYER | 200 | без изменений |

### 11.5. GET `/admin/listings/pending`

Возвращает очередь модерации: `findByStatusIn([PENDING_MODERATION, REJECTED])` —
то есть **включая ранее отклонённые листинги** (доступны к перемодерации).
`Accept-Language` (ru по умолчанию / en).

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| ADMIN-PEND-01 | Есть pending листинги | созданы через POST /listings | 200 | массив содержит `PENDING_MODERATION` |
| ADMIN-PEND-02 | Очередь содержит REJECTED | есть отклонённый листинг | 200 | массив СОДЕРЖИТ rejected (доступен к перемодерации) |
| ADMIN-PEND-03 | Нет ни pending, ни rejected | все ACTIVE/SOLD | 200 | `[]` |
| ADMIN-PEND-04 | Локализация ru | `Accept-Language: ru` | 200 | category/breed на русском |
| ADMIN-PEND-05 | Локализация en | `Accept-Language: en` | 200 | на английском |
| ADMIN-PEND-06 | Пагинация | `page&size` | много в очереди | 200 | корректные meta |
| ADMIN-PEND-07 | Активные/SOLD не попадают | есть ACTIVE/SOLD | 200 | массив их не содержит |

### 11.6. PUT `/admin/listings/{id}/moderate` — `ListingModerateRequest`

`status` `@NotNull` (ListingStatus), `reason` (String). Бизнес-правила:

- Текущий статус листинга должен быть `PENDING_MODERATION` или `REJECTED`
  (иначе `BusinessException` 409 "Only listings with status PENDING_MODERATION or REJECTED can be moderated").
- Запрашиваемый `status` должен быть `ACTIVE` или `REJECTED`
  (иначе `ValidationException` 400 "Moderation result must be ACTIVE or REJECTED").
- `status: ACTIVE` → листинг ACTIVE + `subscriptionService.notifyMatchingSubscribers`
  (email каждому активному подписчику, чьи фильтры подходят).
- В обоих случаях — email продавцу о смене статуса; логируется previous→new с reason.

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| ADMIN-MOD-01 | Одобрить (→ACTIVE) | `status:ACTIVE, reason:null` | листинг PENDING_MODERATION | 200 | листинг ACTIVE; виден в GET /listings |
| ADMIN-MOD-02 | Отклонить (→REJECTED) | `status:REJECTED, reason:"photo violation"` | PENDING_MODERATION | 200 | листинг REJECTED; НЕ виден в GET /listings; попадает в /admin/listings/pending |
| ADMIN-MOD-03 | status null | `status:null` | — | 400 | details → `status` |
| ADMIN-MOD-04 | status = DRAFT (запрещённый результат) | `status:"DRAFT"` | PENDING_MODERATION | 400 | `ValidationException` "Moderation result must be ACTIVE or REJECTED" |
| ADMIN-MOD-05 | status = SOLD (запрещённый результат) | `status:"SOLD"` | PENDING_MODERATION | 400 | то же (только ACTIVE/REJECTED) |
| ADMIN-MOD-06 | Модерация ACTIVE листинга | листинг ACTIVE | — | 409 | "Only listings with status PENDING_MODERATION or REJECTED can be moderated" |
| ADMIN-MOD-07 | Модерация SOLD листинга | листинг SOLD | — | 409 | то же |
| ADMIN-MOD-08 | Повторная модерация REJECTED → ACTIVE | листинг REJECTED | одобрить | 200 | листинг ACTIVE (перемодерация разрешена) |
| ADMIN-MOD-09 | Несуществующий листинг | случайный UUID | — | 404 | "Listing not found" |
| ADMIN-MOD-10 | Уведомление подписчикам при ACTIVE | есть подписка подходящая | одобрить подходящий | 200 | подписчик получил уведомление (через stub/мок) |
| ADMIN-MOD-11 | Нет подходящих подписок | неподходящая подписка | одобрить | 200 | уведомления не уходят |
| ADMIN-MOD-12 | Невалидный enum | `status:"FOO"` | — | 400 | details → `status` |

### 11.7. GET `/admin/reviews/pending`

`ReviewService.getReviewsForModeration(PENDING, pageable)` — только PENDING.

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| ADMIN-REVPEND-01 | Есть pending reviews | созданы отзывы (PENDING) | 200 | все статусы `PENDING` |
| ADMIN-REVPEND-02 | Нет pending | все промодерированы | 200 | `[]` |
| ADMIN-REVPEND-03 | Не показываются APPROVED/REJECTED | есть таковые | 200 | массив их не содержит |
| ADMIN-REVPEND-04 | Пагинация | `page&size` | — | 200 | корректные meta |

### 11.8. PUT `/admin/reviews/{id}/moderate` — `ReviewModerateRequest`

`status` `@NotNull` (ReviewStatus), `reason` (String). Бизнес-правила:

- Нельзя установить статус `PENDING` → `ValidationException` 400
  "Cannot set review status back to PENDING".
- Допустимые результаты: `APPROVED` или `REJECTED`.
- При переходе в `APPROVED` (предыдущий статус ≠ APPROVED) →
  `ReviewService.recalculateRating(recipientId)`: пересчитывает
  `profile.rating` (BigDecimal scale 1 HALF_UP; 0 если нет отзывов) и
  `profile.totalReviews` по ВСЕМ APPROVED отзывам получателя.

| ID | Сценарий | Тело | Предусловия | Статус | Проверки |
|---|---|---|---|---|---|
| ADMIN-REVMOD-01 | Одобрить (APPROVED) | `status:APPROVED` | review PENDING | 200 | review APPROVED; рейтинг продавца пересчитан (totalReviews+1, rating обновлён); отзыв виден в GET /reviews/{userId} посторонним |
| ADMIN-REVMOD-02 | Отклонить (REJECTED) | `status:REJECTED` | review PENDING | 200 | review REJECTED; НЕ виден посторонним в GET /reviews/{userId}; рейтинг НЕ изменился |
| ADMIN-REVMOD-03 | Установить PENDING | `status:PENDING` | — | 400 | `ValidationException` "Cannot set review status back to PENDING" |
| ADMIN-REVMOD-04 | status null | `status:null` | — | 400 | details → `status` |
| ADMIN-REVMOD-05 | Невалидный enum | `status:"FOO"` | — | 400 | details → `status` |
| ADMIN-REVMOD-06 | Повторная модерация APPROVED (APPROVED→APPROVED) | `status:APPROVED` | уже APPROVED | 200 | рейтинг НЕ пересчитывается повторно (переход уже был из APPROVED) |
| ADMIN-REVMOD-07 | REJECTED→APPROVED | `status:APPROVED` | review REJECTED | 200 | рейтинг пересчитан (включает этот отзыв) |
| ADMIN-REVMOD-08 | APPROVED→REJECTED | `status:REJECTED` | review APPROVED | 200 | рейтинг пересчитан (исключает этот отзыв, totalReviews-1) |
| ADMIN-REVMOD-09 | Несуществующий review | случайный UUID | — | 404 | "Review not found" |
| ADMIN-REVMOD-10 | Пересчёт рейтинга с несколькими отзывами | 2+ APPROVED reviews | одобрить новый | 200 | rating = среднее (scale 1 HALF_UP), totalReviews = кол-во APPROVED |
| ADMIN-REVMOD-11 | Рейтинг 0 при отсутствии APPROVED | отклонить единственный APPROVED | — | 200 | rating = 0, totalReviews = 0 |
| ADMIN-REVMOD-12 | Рейтинг в диапазоне 1..5 | отзывы rating=1 и rating=5 | одобрить оба | 200 | rating = 3.0 |

### 11.9. GET `/admin/statistics` — `AdminStatisticsResponse`

Поля: `totalUsers` (`count()`), `activeUsers` (`countByActiveTrue`),
`listingsByStatus` (Map по всем 7 ListingStatus), `bookingsByStatus`
(Map по всем BookingStatus), `reviewsByStatus` (Map по всем ReviewStatus),
`listingsCreatedToday/ThisWeek/ThisMonth` (UTC границы: начало дня / понедельник / 1-е число).
Map-ключи — все значения enum (даже с нулевым счётчиком).

| ID | Сценарий | Предусловия | Статус | Проверки |
|---|---|---|---|---|
| ADMIN-STAT-01 | Пустая платформа | только seed | 200 | `totalUsers` ≥ seed; счётчики согласованы |
| ADMIN-STAT-02 | С пользователями | создано N users | 200 | `totalUsers` = N + база; `activeUsers` ≤ totalUsers |
| ADMIN-STAT-03 | Листинги по статусам | листинги в разных статусах | 200 | `listingsByStatus` содержит ВСЕ 7 ключей ListingStatus; сумма значений = общее число листингов |
| ADMIN-STAT-04 | Бронирования по статусам | bookings в разных статусах | 200 | `bookingsByStatus` содержит все BookingStatus; сумма = общее число bookings |
| ADMIN-STAT-05 | Отзывы по статусам | reviews PENDING/APPROVED/REJECTED | 200 | `reviewsByStatus` содержит все 3 ReviewStatus |
| ADMIN-STAT-06 | listingsCreatedToday | создан листинг сегодня (UTC) | 200 | `listingsCreatedToday >= 1` |
| ADMIN-STAT-07 | listingsCreatedThisWeek/ThisMonth | листинги за период | 200 | week ≥ today, month ≥ week |
| ADMIN-STAT-08 | Map-ключи сериализуются как enum-строки | — | 200 | ключи = "PENDING_MODERATION" и т.д. (Jackson enum-биндинг); регресс Jackson 3 |

---

## 12. Сквозные (cross-cutting) тесты

### 12.1. Безопасность

| ID | Сценарий | Ожидание |
|---|---|---|
| SEC-01 | Любая защищённая ручка без токена | 401 + ApiError JSON (не HTML) |
| SEC-02 | Защищённая ручка с ролью без прав | 403 + ApiError JSON |
| SEC-03 | Неверный формат токена | 401 |
| SEC-04 | Поддельный/переподписанный токен | 401 |
| SEC-05 | Истёкший access-токен | 401 |
| SEC-06 | Публичные ручки работают без токена | 200 |
| SEC-07 | `/admin/**` требует ADMIN или MODERATOR | 403 для BUYER/SELLER |
| SEC-08 | Контекст-path `/api/v1` обязателен | запрос без префикса → 404 |

### 12.2. Валидация (400)

| ID | Сценарий | Ожидание |
|---|---|---|
| VAL-01 | Любое DTO с невалидным полем | 400 + ApiError с `details` (имя поля → сообщение) |
| VAL-02 | Тело не JSON / malformed | 400 |
| VAL-03 | Отсутствует `Content-Type: application/json` | 400/415 |
| VAL-04 | Несериализуемое значение enum в теле | 400 |

### 12.3. 404

| ID | Сценарий | Ожидание |
|---|---|---|
| NF-01 | Несуществующий UUID в path-параметре | 404 + ApiError |
| NF-02 | Неизвестный маршрут | 404 |

### 12.4. Бизнес-ошибки (409)

Все 409-кейсы описаны в модулях выше. Сводка переходов:

- **Listing**: `PENDING_MODERATION → ACTIVE/REJECTED` (админ); `ACTIVE → RESERVED` (confirm booking); `RESERVED → SOLD` (complete); `RESERVED → ACTIVE` (cancel confirmed booking).
- **Booking**: `PENDING → CONFIRMED → COMPLETED`; `→ CANCELLED` из PENDING/CONFIRMED.
- **Review**: `PENDING → APPROVED/REJECTED`; запрет возврата в PENDING.
- **Auth**: дублирующий email; неверные креды; не подтверждённый/disabled; невалидный/истёкший refresh/reset/verify токен.

### 12.5. Пагинация

| ID | Сценарий | Ожидание |
|---|---|---|
| PAGE-01 | Стандартная пагинация | `content`, `pageable`, `totalElements`, `totalPages` |
| PAGE-02 | size больше имеющихся | `content.size() <= size`, `totalPages=1` |
| PAGE-03 | page за пределами | `content: []` |
| PAGE-04 | sort по полю | корректный порядок |

### 12.6. Локализация (Accept-Language)

| ID | Сценарий | Ожидание |
|---|---|---|
| I18N-01 | Заголовок `ru` | имена на русском |
| I18N-02 | Заголовок `en` | имена на английском |
| I18N-03 | Без заголовка (default `ru`) | русский |
| I18N-04 | Незнакомый язык (fallback) | дефолтный (ru или en — проверить) |

### 12.7. Формат ApiError

| ID | Сценарий | Ожидание |
|---|---|---|
| ERR-01 | Структура ApiError на 409 | `timestamp`, `status`, `error`, `message`, `path`; null-поля отсутствуют |
| ERR-02 | Структура ApiError на 401 (entry point) | JSON, не HTML (Jackson 3 `tools.jackson.databind.ObjectMapper`) |
| ERR-03 | Структура ApiError на 403 (denied handler) | JSON, не HTML |
| ERR-04 | Структура ApiError на 404 | — |
| ERR-05 | Структура ApiError на 400 | содержит `details` для валидации |

---

## 13. Полные сквозные сценарии (end-to-end flows)

### 13.1. FLOW-PURCHASE — полный цикл покупки с отзывом

1. Создать seller и buyer (`createUser`).
2. Seller: `POST /listings` → `PENDING_MODERATION` (201).
3. Admin: `PUT /admin/listings/{id}/moderate` `ACTIVE` → 200.
4. Buyer: `POST /listings/{id}/book` → 201 (PENDING). Проверить листинг остался ACTIVE.
5. Seller: `PUT /bookings/{id}/confirm` → 200. Листинг → RESERVED.
6. Seller: `PUT /bookings/{id}/complete` → 200. Листинг → SOLD.
7. Buyer: `POST /reviews` rating=5 → 201 (PENDING).
8. Admin: `PUT /admin/reviews/{id}/moderate` APPROVED → 200.
9. Проверить: `GET /users/{sellerId}/reviews` содержит отзыв; `rating` продавца = 5, `totalReviews` = 1.
10. Admin: `GET /admin/statistics` — счётчики согласованы (listings SOLD=1, bookings COMPLETED=1, reviews APPROVED=1).

### 13.2. FLOW-CANCEL — подтверждение и отмена

1. Создать seller, buyer.
2. Создать листинг, промодерировать в ACTIVE.
3. Buyer: book → PENDING.
4. Seller: confirm → CONFIRMED; листинг RESERVED.
5. Buyer: cancel → CANCELLED; листинг возвращён в ACTIVE.
6. Проверить: листинг снова виден в `GET /listings`; повторный book возможен.

### 13.3. FLOW-MESSAGING — переписка и read-receipts

1. Создать userA, userB.
2. A: `POST /messages` (receiverId=B, text) → 201.
3. A: `GET /messages` → диалог с B, unread у B.
4. B: `GET /messages/{A}` → история содержит сообщение.
5. B: `PUT /messages/{id}/read` → 200.
6. B: `GET /messages` → счётчик unread = 0.

### 13.4. FLOW-SUBSCRIPTION — уведомление подписчику

1. Создать buyer, создать подписку buyer с фильтром (categoryId=DOGS).
2. Seller создаёт листинг (DOGS) → PENDING_MODERATION.
3. Admin одобряет → 200.
4. Проверить: подписчик получил уведомление (EmailSenderStub/мок).
5. Создать листинг с неподходящим фильтром (CATS) → одобрить → уведомление НЕ уходит.

### 13.5. FLOW-RATING — пересчёт рейтинга

1. Seller S, buyers B1, B2, B3.
2. Создать 3 листинга, модерировать, провести 3 завершённых booking.
3. Оставить отзывы с rating 5, 3, 1.
4. Одобрить все 3 через admin.
5. Проверить: `S.rating = 3.0`, `totalReviews = 3`.
6. Отклонить один отзыв (REJECTED) — проверить, что рейтинг пересчитан (исключая rejected).

---

## 14. Список файлов тестов (предлагаемая структура)

```
src/test/java/com/petmarketplace/application/
├── auth/controller/AuthControllerTest.java          (существует — дополнить)
├── user/controller/UserControllerTest.java          (новый)
├── category/controller/CategoryControllerTest.java  (новый)
├── listing/controller/ListingControllerTest.java    (существует — дополнить)
├── booking/controller/BookingControllerTest.java    (существует — дополнить)
├── message/controller/MessageControllerTest.java    (существует — дополнить)
├── review/controller/ReviewControllerTest.java      (новый)
├── favorite/controller/FavoriteControllerTest.java  (новый)
├── subscription/controller/SubscriptionControllerTest.java (новый)
└── admin/controller/AdminControllerTest.java        (новый — детальный, §11)
src/test/java/com/petmarketplace/crosscutting/
├── SecurityTest.java
├── ValidationErrorTest.java
└── ApiErrorFormatTest.java
```

### Порядок внедрения

1. Дополнить `IntegrationTestBase` хелпером создания активного листинга (§1.3),
   если ещё нет.
2. Реализовать новые тест-классы по модулям, начиная с admin (наиболее детальный).
3. Дополнить существующие тесты недостающими кейсами.
4. Сквозные flow-тесты (§13) — отдельные методы или классы.

---

## 15. Проверка покрытия

Каждый ID-кейс из §2–§13 должен иметь соответствующий `@Test`-метод.
Чек-лист при ревью PR:

- [ ] Все кейсы модуля AUTH (§2) — 31 кейс.
- [ ] Все кейсы USER (§3) — ~28 кейсов.
- [ ] CATEGORY (§4) — 9 кейсов.
- [ ] LISTING (§5) — ~55 кейсов.
- [ ] BOOKING (§6) — ~28 кейсов.
- [ ] MESSAGE (§7) — ~21 кейс.
- [ ] REVIEW (§8) — ~19 кейсов.
- [ ] FAVORITE (§9) — ~11 кейсов.
- [ ] SUBSCRIPTION (§10) — ~12 кейсов.
- [ ] ADMIN (§11) — ~70 кейсов (включая 32 кейса доступа + детальная модерация).
- [ ] Сквозные (§12) — ~30 кейсов.
- [ ] Flow-тесты (§13) — 5 сценариев.

Итого ≈ 320 тест-кейсов.