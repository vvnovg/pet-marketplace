# DB Demo Data Seeding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate the production demo stack (Docker Compose, `prod` profile) with ~250 rows of realistic, status-consistent marketplace data via two idempotent Liquibase seed changesets, while keeping Testcontainers integration tests on a clean DB.

**Architecture:** Two new Liquibase YAML changesets included from `db.changelog-master.yaml`: `005` fills the reference-data gap (breeds for `reptiles`/`fish`/`other`) and runs in every context; `006` seeds all demo business data (users → profiles → listings → images → bookings → reviews → messages → favorites → subscriptions) under `contextFilter: "prod or dev or stand"` with a `count(users)==0` guard. Profile config files set `liquibase.contexts` so `test` (Testcontainers) skips `006`. One shared BCrypt hash authenticates all demo accounts with password `Demo12345`.

**Tech Stack:** Spring Boot 4.x, Liquibase (YAML changelogs), PostgreSQL 16, BCrypt (`$2b$10$`), JDK 26, Gradle 9, Docker Compose.

## Global Constraints

- JDK 26 + Gradle 9 (system `gradle`, NOT `./gradlew` which targets Gradle 8.14). If `java` missing: `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.
- `hibernate.ddl-auto=none` — all schema/data via Liquibase only.
- UUID PKs have NO DB default — every `insert` must supply an explicit UUID string.
- Demo user domain is `@demo.local` — NEVER `@example.com` (stand tests delete `LIKE '%@example.com'`).
- Demo password `Demo12345` → shared BCrypt hash `$2b$10$dJJWtO4l9OGtmdo74ehD2eVJJWYSKInvhEwWZsWU8BoUvSotrICa.` (Spring `BCryptPasswordEncoder` accepts `$2b$`).
- `is_verified=true` on all demo users so `/auth/login` works without email confirmation.
- Existing reference UUIDs (from `001`): dogs `11111111-1111-1111-1111-111111111111`, cats `22222222-2222-2222-2222-222222222222`, birds `33333333-3333-3333-3333-333333333333`, rodents `44444444-4444-4444-4444-444444444444`, reptiles `55555555-5555-5555-5555-555555555555`, fish `66666666-6666-6666-6666-666666666666`, other `77777777-7777-7777-7777-777777777777`. Existing breed UUIDs: dogs `10000000-0000-0000-0000-000000000001..005`, cats `20000000-...001..005`, birds `30000000-...001..003`, rodents `40000000-...001..003`.
- Status-invariant pairs (MUST hold in seeded data): `booking CONFIRMED ↔ listing RESERVED`, `booking COMPLETED ↔ listing SOLD`, `booking CANCELLED ↔ listing ACTIVE`, `booking PENDING ↔ listing ACTIVE`. DRAFT/PENDING_MODERATION listings have NO bookings.
- Timestamps: AuditEntity tables (`users`, `profiles`, `listings`, `bookings`, `reviews`) have `created_at`/`updated_at` with DB `now()` default — omit in inserts. Base + `@CreatedDate` tables (`messages`, `favorites`, `subscriptions`) rely on `created_at` `now()` default — omit. If a specific order is needed (messages), supply `created_at` explicitly as `'2026-07-01 10:00:00+03'` incrementing.
- Commit message style: conventional commits, end with `Co-Authored-By: Claude <noreply@anthropic.com>`. Commit per task.
- After editing any Liquibase YAML, validate syntax with: `python3 -c "import yaml; yaml.safe_load(open('<file>'))"` (yaml is stdlib-available via the system Python).

---

## File Structure

| File | Responsibility | New/Modify |
|---|---|---|
| `src/main/resources/db/changelog/changelogs/005-seed-breeds-gap.yaml` | Adds missing breeds for reptiles/fish/other; per-row idempotent (renumbered from 002 to avoid collision with pre-existing 002-add-profile-audit-columns) | Create |
| `src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml` | All demo business data, single guarded changeset | Create |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | Includes `005` and `006` (after 004) | Modify |
| `src/main/resources/application.yml` | `liquibase.contexts: dev` | Modify |
| `src/main/resources/application-prod.yml` | `liquibase.contexts: prod` | Modify |
| `src/test/resources/application-stand.yml` | `liquibase.contexts: stand` | Modify |
| `src/test/resources/application-test.yml` | `liquibase.contexts: test` | Modify |
| `README.md` | "Demo data" section | Modify |

`006` is built incrementally across Tasks 2–5 (each task appends a block to the same file). The master `include` and context wiring land in Task 6 so `006` only applies once it is complete and wired.

---

### Task 1: Breeds gap-fill changeset (`005`)

**Files:**
- Create: `src/main/resources/db/changelog/changelogs/005-seed-breeds-gap.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`

**Interfaces:**
- Produces: breeds rows for reptiles/fish/other, referenced later by `006` listings via `breed_id`.

- [ ] **Step 1: Read the existing master changelog**

Run: `sed -n '1,40p' src/main/resources/db/changelog/db.changelog-master.yaml`
Note the `include:` block pattern and the `databaseChangeLog:` root key.

- [ ] **Step 2: Create `005-seed-breeds-gap.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 021-seed-breeds-reptiles
      author: seed
      preConditions:
        - onFail: MARK_RAN
        - sqlCheck:
            expectedResult: 0
            sql: SELECT count(*) FROM breeds WHERE id = '55500000-0000-0000-0000-000000000001'
      changes:
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '55500000-0000-0000-0000-000000000001' }
              - column: { name: category_id, value: '55555555-5555-5555-5555-555555555555' }
              - column: { name: name_ru, value: 'Хорнетная змея' }
              - column: { name: name_en, value: 'Corn Snake' }
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '55500000-0000-0000-0000-000000000002' }
              - column: { name: category_id, value: '55555555-5555-5555-5555-555555555555' }
              - column: { name: name_ru, value: 'Бородатая агама' }
              - column: { name: name_en, value: 'Bearded Dragon' }
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '55500000-0000-0000-0000-000000000003' }
              - column: { name: category_id, value: '55555555-5555-5555-5555-555555555555' }
              - column: { name: name_ru, value: 'Шаровидный питон' }
              - column: { name: name_en, value: 'Ball Python' }
  - changeSet:
      id: 022-seed-breeds-fish
      author: seed
      preConditions:
        - onFail: MARK_RAN
        - sqlCheck:
            expectedResult: 0
            sql: SELECT count(*) FROM breeds WHERE id = '66600000-0000-0000-0000-000000000001'
      changes:
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '66600000-0000-0000-0000-000000000001' }
              - column: { name: category_id, value: '66666666-6666-6666-6666-666666666666' }
              - column: { name: name_ru, value: 'Гуппи' }
              - column: { name: name_en, value: 'Guppy' }
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '66600000-0000-0000-0000-000000000002' }
              - column: { name: category_id, value: '66666666-6666-6666-6666-666666666666' }
              - column: { name: name_ru, value: 'Золотая рыбка' }
              - column: { name: name_en, value: 'Goldfish' }
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '66600000-0000-0000-0000-000000000003' }
              - column: { name: category_id, value: '66666666-6666-6666-6666-666666666666' }
              - column: { name: name_ru, value: 'Скалярия' }
              - column: { name: name_en, value: 'Angelfish' }
  - changeSet:
      id: 023-seed-breeds-other
      author: seed
      preConditions:
        - onFail: MARK_RAN
        - sqlCheck:
            expectedResult: 0
            sql: SELECT count(*) FROM breeds WHERE id = '77700000-0000-0000-0000-000000000001'
      changes:
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '77700000-0000-0000-0000-000000000001' }
              - column: { name: category_id, value: '77777777-7777-7777-7777-777777777777' }
              - column: { name: name_ru, value: 'Морская свинка' }
              - column: { name: name_en, value: 'Guinea Pig' }
        - insert:
            tableName: breeds
            columns:
              - column: { name: id, value: '77700000-0000-0000-0000-000000000002' }
              - column: { name: category_id, value: '77777777-7777-7777-7777-777777777777' }
              - column: { name: name_ru, value: 'Феррет' }
              - column: { name: name_en, value: 'Ferret' }
```

- [ ] **Step 3: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/db/changelog/changelogs/005-seed-breeds-gap.yaml'))"`
Expected: no output (valid).

- [ ] **Step 4: Include `005` in the master changelog**

Add the include lines (matching the existing `001` include pattern) to `db.changelog-master.yaml`:
```yaml
  - include:
      file: changelogs/005-seed-breeds-gap.yaml
      relativeToChangelogFile: true
```

- [ ] **Step 5: Build to confirm resources compile**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/005-seed-breeds-gap.yaml src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(seed): fill breeds gap for reptiles/fish/other

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: `006` scaffolding + users + profiles

**Files:**
- Create: `src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml`

**Interfaces:**
- Produces: `users` rows (UUIDs `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001`..`0047` scheme — see below) and `profiles` rows (1:1), referenced by later tasks as `seller_id`/`buyer_id`/`author_id`/`recipient_id`/`sender_id`/`receiver_id`.

**UUID scheme for users:** base `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa` + 4-digit suffix `0001..0047` (e.g. `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001`). Map: `0001`=admin, `0002`=moderator, `0003..0007`=seller1..5, `0008`=buyer (explicit demo), `0009..0018`=seller6..15, `0019..0047`=buyer1..29.

**Role map:** `0001`=ADMIN, `0002`=MODERATOR, `0003..0018`=SELLER (15 sellers), `0019..0047`=BUYER (29 buyers).

- [ ] **Step 1: Create `006-seed-demo-data.yaml` with header + guard + users + profiles**

```yaml
databaseChangeLog:
  - changeSet:
      id: 024-seed-demo-users
      author: seed
      contextFilter: "prod or dev or stand"
      preConditions:
        - onFail: MARK_RAN
        - sqlCheck:
            expectedResult: 0
            sql: SELECT count(*) FROM users
      changes:
        # --- admin ---
        - insert:
            tableName: users
            columns:
              - column: { name: id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001' }
              - column: { name: email, value: 'admin@demo.local' }
              - column: { name: password_hash, value: '$2b$10$dJJWtO4l9OGtmdo74ehD2eVJJWYSKInvhEwWZsWU8BoUvSotrICa.' }
              - column: { name: role, value: 'ADMIN' }
              - column: { name: first_name, value: 'Алексей' }
              - column: { name: last_name, value: 'Админов' }
              - column: { name: is_verified, valueBoolean: true }
              - column: { name: is_active, valueBoolean: true }
        # --- moderator ---
        - insert:
            tableName: users
            columns:
              - column: { name: id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002' }
              - column: { name: email, value: 'moderator@demo.local' }
              - column: { name: password_hash, value: '$2b$10$dJJWtO4l9OGtmdo74ehD2eVJJWYSKInvhEwWZsWU8BoUvSotrICa.' }
              - column: { name: role, value: 'MODERATOR' }
              - column: { name: first_name, value: 'Мария' }
              - column: { name: last_name, value: 'Модераторова' }
              - column: { name: is_verified, valueBoolean: true }
              - column: { name: is_active, valueBoolean: true }
        # --- seller1..5 (explicit demo sellers) ---
        # Repeat insert block for IDs 0003..0007 with role SELLER.
        # seller1: id 0003, email seller1@demo.local, first_name 'Иван', last_name 'Иванов', city Москва
        # seller2: id 0004, email seller2@demo.local, first_name 'Петр', last_name 'Петров', city Санкт-Петербург
        # seller3: id 0005, email seller3@demo.local, first_name 'Светлана', last_name 'Светлова', city Казань
        # seller4: id 0006, email seller4@demo.local, first_name 'Дмитрий', last_name 'Дмитриев', city Новосибирск
        # seller5: id 0007, email seller5@demo.local, first_name 'Елена', last_name 'Еленова', city Екатеринбург
        # --- buyer (explicit demo) ---
        # id 0008, email buyer@demo.local, role BUYER, first_name 'Олег', last_name 'Олегов', city Москва
        # --- seller6..15 ---
        # IDs 0009..0018, emails seller6@demo.local .. seller15@demo.local, role SELLER.
        # Cycle first_name/last_name/city through: ['Андрей','Андреев','Москва'], ['Ольга','Ольгина','Санкт-Петербург'],
        # ['Рустам','Рустамов','Казань'], ['Наталья','Натальина','Новосибирск'], ['Сергей','Сергеев','Екатеринбург'],
        # ['Татьяна','Татьянова','Москва'], ['Павел','Павлов','Санкт-Петербург'], ['Юлия','Юрьева','Казань'],
        # ['Виктор','Викторов','Новосибирск'], ['Ирина','Иринина','Екатеринбург'].
        # --- buyer1..29 ---
        # IDs 0019..0047, emails buyer1@demo.local .. buyer29@demo.local, role BUYER.
        # Cycle the same 10 name/city triples, then repeat.
```

**Expand rule:** write out all 47 insert blocks fully (no comments in the final file — the comment lines above are the generation spec). Each user insert uses the exact column set shown for admin: `id, email, password_hash, role, first_name, last_name, is_verified=true, is_active=true`. All share the same `password_hash` literal. `is_verified` and `is_active` are `valueBoolean: true`.

- [ ] **Step 2: Append the profiles block to the same changeset**

Add a second `changeSet` `id: 025-seed-demo-profiles` (same `author: seed`, same `contextFilter`, same `preConditions` `count(users)==0 → MARK_RAN`) containing 47 `insert` blocks into `profiles`, one per user, columns:
```yaml
              - column: { name: user_id, value: '<matching user id>' }
              - column: { name: country, value: 'Россия' }
              - column: { name: city, value: '<city from user>' }
              - column: { name: bio, value: 'Продавец домашних животных с опытом. Звоните и пишите!' }
              - column: { name: rating, valueNumeric: 0.0 }
              - column: { name: total_reviews, valueNumeric: 0 }
```
For ADMIN/MODERATOR users use bio `'Сотрудник платформы.'`. `rating`/`total_reviews` start at 0; Task 4 updates sellers with approved reviews.

- [ ] **Step 3: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml'))"`
Expected: no output.

- [ ] **Step 4: Build**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL (resources compile; `006` not yet included in master so it does not apply).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml
git commit -m "feat(seed): demo users and profiles (003 part 1/4)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: `006` listings + listing_images

**Files:**
- Modify: `src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml` (append)

**Interfaces:**
- Consumes: `users` (seller_id from IDs `0003..0018`), categories (the 7 fixed UUIDs), breeds (existing 16 + new 8 from `005`).
- Produces: `listings` rows (UUIDs `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001`..`0100`) and `listing_images`, referenced later by bookings/reviews/messages/favorites.

**Listing UUID scheme:** base `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb` + 4-digit suffix `0001..0100`.

**Status distribution (100 listings total — gap-free, consistent with Task 4 booking pairs):**
- `0001..0062`: ACTIVE (62) — PENDING and CANCELLED bookings attach here
- `0063..0070`: RESERVED (8) — paired 1:1 with CONFIRMED bookings in Task 4
- `0071..0090`: SOLD (20) — paired 1:1 with COMPLETED bookings in Task 4
- `0091..0100`: PENDING_MODERATION (10) — no bookings
- (no DRAFT — dropped so the listing/booking/review arithmetic is exact)

**Seller assignment:** listings `0001..0100` cycle through seller IDs `0003..0018` (15 sellers) round-robin.

**Category/breed assignment:** cycle through all 7 categories with their breeds (dogs:5, cats:5, birds:3, rodents:3, reptiles:3, fish:3, other:2 = 24 breed slots); for each listing pick category[i % 7] and a breed within it (breed[j % breeds_in_cat]).

- [ ] **Step 1: Append the listings changeSet**

`id: 026-seed-demo-listings`, same `author/contextFilter/preConditions`. 100 `insert` blocks into `listings`. Each insert columns:
```yaml
              - column: { name: id, value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb<NNNN>' }
              - column: { name: seller_id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa<seller>' }
              - column: { name: category_id, value: '<one of 7 category UUIDs>' }
              - column: { name: breed_id, value: '<breed UUID in that category, or null>' }
              - column: { name: title, value: '<e.g. Лабрадор-ретривер щенок' }
              - column: { name: description, value: '<2-3 sentence realistic RU description>' }
              - column: { name: price, valueNumeric: <5000..150000> }
              - column: { name: currency, value: 'RUB' }
              - column: { name: gender, value: 'MALE' }   # alternate MALE/FEMALE
              - column: { name: age_months, valueNumeric: <1..36> }
              - column: { name: color, value: '<RU color>' }
              - column: { name: has_vaccination, valueBoolean: <true|false> }
              - column: { name: has_documents, valueBoolean: <true|false> }
              - column: { name: location_country, value: 'Россия' }
              - column: { name: location_city, value: '<seller city>' }
              - column: { name: status, value: '<ACTIVE|RESERVED|SOLD|PENDING_MODERATION per distribution>' }
              - column: { name: views_count, valueNumeric: <0..500> }
```
Columns are nullable except `seller_id`, `category_id`, `status`, `has_vaccination` (DB default false), `has_documents` (DB default false), `views_count` (DB default 0). Supply all of: `id, seller_id, category_id, breed_id` (nullable — pass null for `other` category if desired, but new `other` breeds from `005` exist so prefer using a breed), `title, description, price, currency, gender, age_months, color, has_vaccination, has_documents, location_country, location_city, status, views_count` for ACTIVE/RESERVED/SOLD; for PENDING_MODERATION you may omit `price`/`gender`/`age_months`/`color` (DB defaults/nullable) but keep `title`/`description` so moderation has something to show.

For `breed_id`: category `other` (`77777777-...`) — use one of `77700000-...001/002`. For fish/reptiles use the new breeds from `005`.

- [ ] **Step 2: Append the listing_images changeSet**

`id: 027-seed-demo-listing-images`, same guard. ~150 inserts (1-2 per listing; 1 image for listings `0001..0050`, 2 images for `0051..0100`). UUID scheme `cccccccc-cccc-cccc-cccc-cccccccc0001`..`0150`. Columns:
```yaml
              - column: { name: id, value: 'cccccccc-cccc-cccc-cccc-cccccccc<NNNN>' }
              - column: { name: listing_id, value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb<listing>' }
              - column: { name: url, value: 'https://picsum.photos/seed/<listing-id-suffix>/600' }
              - column: { name: order_index, valueNumeric: 0 }   # 1 for second image
              - column: { name: is_main, valueBoolean: true }   # false for second image
```
For each listing, the first image has `order_index=0, is_main=true`; a second image (listings 0051+) has `order_index=1, is_main=false`.

- [ ] **Step 3: Validate YAML + build**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml'))"` then `gradle build -x test`
Expected: no output, then BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml
git commit -m "feat(seed): demo listings and images (003 part 2/4)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: `006` bookings + reviews + profile rating update

**Files:**
- Modify: `src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml` (append)

**Interfaces:**
- Consumes: listings (status already set per Task 3), users (buyers `0019..0047`, sellers `0003..0018`).
- Produces: `bookings` (UUIDs `dddddddd-dddd-dddd-dddd-dddddddd0001`..`0040`), `reviews` (UUIDs `eeeeeeee-eeee-eeee-eeee-eeeeeeee0001`..`0020`).

**Booking ↔ listing status pairs (40 bookings total, MUST match Task 3 listing statuses):**
- bookings `0001..0007` (7): PENDING → listings `0001..0007` (ACTIVE)
- bookings `0008..0015` (8): CONFIRMED → listings `0063..0070` (RESERVED)
- bookings `0016..0035` (20): COMPLETED → listings `0071..0090` (SOLD)
- bookings `0036..0040` (5): CANCELLED → listings `0008..0012` (ACTIVE)

**Buyer assignment:** cycle buyers `0019..0047`. `seller_id` = the listing's seller (look up from Task 3 assignment). `buyer_id != seller_id`.

- [ ] **Step 1: Append the bookings changeSet**

`id: 028-seed-demo-bookings`, same guard. 40 `insert` blocks into `bookings`. Columns:
```yaml
              - column: { name: id, value: 'dddddddd-dddd-dddd-dddd-dddddddd<NNNN>' }
              - column: { name: listing_id, value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb<listing-per-pair>' }
              - column: { name: buyer_id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa<buyer>' }
              - column: { name: seller_id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa<seller-of-listing>' }
              - column: { name: status, value: '<PENDING|CONFIRMED|COMPLETED|CANCELLED per pair>' }
              - column: { name: message, value: 'Здравствуйте, хочу купить. Звоните.' }
```
Map each booking to its paired listing per the table above. Ensure `listing_id` status matches `booking` status (verified in Task 8).

- [ ] **Step 2: Append the reviews changeSet**

`id: 029-seed-demo-reviews`, same guard. 20 `insert` blocks into `reviews`, each on a distinct COMPLETED booking (bookings `0016..0035` provide exactly 20 COMPLETED bookings — one review per booking satisfies the unique `booking_id` constraint). Reviews `0001..0015` → status APPROVED (on completed bookings `0016..0030`); reviews `0016..0020` → status PENDING (on completed bookings `0031..0035`).

  Each review insert columns:
```yaml
              - column: { name: id, value: 'eeeeeeee-eeee-eeee-eeee-eeeeeeee<NNNN>' }
              - column: { name: author_id, value: '<buyer of that booking>' }
              - column: { name: recipient_id, value: '<seller of that booking/listing>' }
              - column: { name: booking_id, value: 'dddddddd-dddd-dddd-dddd-dddddddd<completed-booking>' }
              - column: { name: rating, valueNumeric: <1..5> }
              - column: { name: comment, value: '<RU review text matching rating>' }
              - column: { name: status, value: 'APPROVED' }   # first 15; last 5 = 'PENDING'
```
Reviews `0001..0015` → APPROVED (on completed bookings `0016..0030`), `0016..0020` → PENDING (on completed bookings `0031..0035`).

- [ ] **Step 3: Append the profile rating UPDATE**

`id: 030-seed-demo-profile-ratings`, same guard. One `update` per seller that received approved reviews, setting `rating` = average of their approved reviews (rounded to 1 decimal) and `total_reviews` = count. Use `sql` raw blocks:
```yaml
      changes:
        - sql:
            sql: UPDATE profiles p SET rating = COALESCE((SELECT ROUND(AVG(r.rating)::numeric, 1) FROM reviews r WHERE r.recipient_id = p.user_id AND r.status = 'APPROVED'), 0.0), total_reviews = COALESCE((SELECT count(*) FROM reviews r WHERE r.recipient_id = p.user_id AND r.status = 'APPROVED'), 0)
```
A single update covers all sellers at once (no per-seller hardcoding needed).

- [ ] **Step 4: Validate YAML + build**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml'))"` then `gradle build -x test`
Expected: no output, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml
git commit -m "feat(seed): demo bookings, reviews, profile ratings (003 part 3/4)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: `006` messages + favorites + subscriptions

**Files:**
- Modify: `src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml` (append)

**Interfaces:**
- Consumes: users, listings. Produces: `messages` (UUIDs `12121212-1212-1212-1212-121212120001`..`0030`), `favorites` (`13131313-...`), `subscriptions` (`14141414-...`).

- [ ] **Step 1: Append messages changeSet**

`id: 031-seed-demo-messages`, same guard. ~30 inserts across 6 chats (each chat = a buyer↔seller pair around one listing). UUID scheme `12121212-1212-1212-1212-12121212<NNNN>` (last group = 8 hex + 4-digit suffix). Columns:
```yaml
              - column: { name: id, value: '12121212-1212-1212-1212-12121212<NNNN>' }
              - column: { name: sender_id, value: '<one side>' }
              - column: { name: receiver_id, value: '<other side>' }
              - column: { name: listing_id, value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb<listing>' }
              - column: { name: content, value: '<RU chat line>' }
              - column: { name: is_read, valueBoolean: <true|false> }
              - column: { name: created_at, valueComputed: "'2026-07-01 10:00:00+03' + interval '<N> minute'" }
```
6 chats: (buyer19↔seller3, listing 0001), (buyer20↔seller4, listing 0002), (buyer21↔seller5, listing 0003), (buyer22↔seller6, listing 0004), (buyer23↔seller7, listing 0005), (buyer24↔seller8, listing 0006). 5 messages per chat, alternating sender, `created_at` incrementing by 2 minutes. Last message in each chat `is_read=false`, earlier `true`.

- [ ] **Step 2: Append favorites changeSet**

`id: 032-seed-demo-favorites`, same guard. ~25 inserts. UUID scheme `13131313-1313-1313-1313-13131313<NNNN>` (last group = 8 hex + 4-digit suffix, 0001..0025). Columns:
```yaml
              - column: { name: id, value: '13131313-1313-1313-1313-13131313<NNNN>' }
              - column: { name: user_id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa<buyer>' }
              - column: { name: listing_id, value: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb<ACTIVE listing>' }
```
Assign buyers `0019..0043` to ACTIVE listings `0001..0025` (unique pairs — each buyer favorites one distinct listing). Never favorite a SOLD/RESERVED listing.

- [ ] **Step 3: Append subscriptions changeSet**

`id: 033-seed-demo-subscriptions`, same guard. ~10 inserts. UUID scheme `14141414-1414-1414-1414-14141414<NNNN>` (last group = 8 hex + 4-digit suffix, 0001..0010). Columns:
```yaml
              - column: { name: id, value: '14141414-1414-1414-1414-14141414<NNNN>' }
              - column: { name: user_id, value: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa<buyer>' }
              - column: { name: filters, value: '{"categoryId":"<uuid>","city":"<RU city>","maxPrice":<n>}' }
              - column: { name: is_active, valueBoolean: true }
```
Assign buyers `0019..0028`. Vary `categoryId` across the 7 categories, `city` across 5 cities, `maxPrice` 30000..120000. `filters` is a literal JSON string (JSONB column).

- [ ] **Step 4: Validate YAML + build**

Run: `python3 -c "import yaml; yaml.safe_load(open('src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml'))"` then `gradle build -x test`
Expected: no output, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/006-seed-demo-data.yaml
git commit -m "feat(seed): demo messages, favorites, subscriptions (003 part 4/4)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Wire `006` into master + Liquibase contexts

**Files:**
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`
- Modify: `src/test/resources/application-stand.yml`
- Modify: `src/test/resources/application-test.yml`

- [ ] **Step 1: Include `006` in the master changelog**

Add to `db.changelog-master.yaml` after the `005` include (i.e. as the last include, after `004-add-subscription-indexes.yaml` and `005-seed-breeds-gap.yaml`):
```yaml
  - include:
      file: changelogs/006-seed-demo-data.yaml
      relativeToChangelogFile: true
```

- [ ] **Step 2: Add `liquibase.contexts` to each profile config**

In `src/main/resources/application.yml` (dev/default), under the existing `spring.liquibase` block (or `liquibase:` top-level — check current key; Boot 4 uses `spring.liquibase`), add:
```yaml
spring:
  liquibase:
    contexts: dev
```
If a `spring.liquibase` block already exists (it does — `liquibase.enabled: true` per spec), add `contexts: dev` to it rather than creating a new block. Confirm the actual key path by reading the file first.

In `src/main/resources/application-prod.yml` add `contexts: prod` to its liquibase config.

In `src/test/resources/application-stand.yml` add `contexts: stand` (the file already sets `liquibase.enabled: false`; add `contexts: stand` to the same block — harmless, only matters if stand Liquibase is re-enabled).

In `src/test/resources/application-test.yml` add `contexts: test`.

- [ ] **Step 3: Validate YAML of all touched files**

Run:
```bash
python3 -c "import yaml; [yaml.safe_load(open(f)) for f in ['src/main/resources/application.yml','src/main/resources/application-prod.yml','src/test/resources/application-stand.yml','src/test/resources/application-test.yml','src/main/resources/db/changelog/db.changelog-master.yaml']]"
```
Expected: no output.

- [ ] **Step 4: Build**

Run: `gradle build -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/db.changelog-master.yaml src/main/resources/application.yml src/main/resources/application-prod.yml src/test/resources/application-stand.yml src/test/resources/application-test.yml
git commit -m "feat(seed): wire 003 into master and set liquibase contexts

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: README demo data section

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Demo data" section to README.md**

Insert after the "Deploy (backend distribution)" section. Content:
```markdown
## Demo data

After `docker compose -f docker-compose.yml up -d --build` on an empty database, Liquibase seeds demo data automatically (changeset `006-seed-demo-data.yaml`, guarded so it runs only when `users` is empty):

- ~47 users, ~100 listings, ~40 bookings, ~20 reviews, ~30 messages, ~25 favorites, ~10 subscriptions.
- Listing/booking statuses are seeded in consistent pairs (CONFIRMED↔RESERVED, COMPLETED↔SOLD).

**Demo accounts** (all share password `Demo12345`, email-verified):

| Email | Role |
|---|---|
| admin@demo.local | ADMIN |
| moderator@demo.local | MODERATOR |
| seller1@demo.local .. seller5@demo.local | SELLER |
| buyer@demo.local | BUYER |

Log in: `POST /api/v1/auth/login` with `{"email":"admin@demo.local","password":"Demo12345"}`.

**Re-seed from scratch:** `docker compose -f docker-compose.yml down -v && docker compose -f docker-compose.yml up -d --build` (the `-v` wipes the volume so the `users-empty` guard re-triggers seeding).

Integration tests (Testcontainers) run under the `test` Liquibase context and skip `006`, so they keep a clean database.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(deploy): document demo data seeding and accounts

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: Full verification

- [ ] **Step 1: Start the stack from scratch**

Run:
```bash
docker compose -f docker-compose.yml down -v
docker compose -f docker-compose.yml up -d --build
```
Wait ~60s, then: `curl -fsS http://localhost:8080/api/v1/actuator/health`
Expected: `{"status":"UP",...}`. Check logs for Liquibase applying `021..033`:
`docker logs petmarketplace-app 2>&1 | grep -iE "ChangeSet|021-|033-"` — expect `021..033` marked `EXECUTED`.

- [ ] **Step 2: Verify row counts**

Run:
```bash
docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c "
SELECT 'users' t, count(*) FROM users
UNION ALL SELECT 'profiles', count(*) FROM profiles
UNION ALL SELECT 'listings', count(*) FROM listings
UNION ALL SELECT 'listing_images', count(*) FROM listing_images
UNION ALL SELECT 'bookings', count(*) FROM bookings
UNION ALL SELECT 'reviews', count(*) FROM reviews
UNION ALL SELECT 'messages', count(*) FROM messages
UNION ALL SELECT 'favorites', count(*) FROM favorites
UNION ALL SELECT 'subscriptions', count(*) FROM subscriptions;"
```
Expected: users 47, profiles 47, listings 100, listing_images ~150, bookings 40, reviews 20, messages 30, favorites 25, subscriptions 10. Also verify breeds gap closed:
`docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c "SELECT c.slug, count(b.*) FROM categories c LEFT JOIN breeds b ON b.category_id=c.id GROUP BY c.slug ORDER BY c.slug;"`
Expected: every category ≥ 2 breeds (reptiles/fish/other now 3/3/2).

- [ ] **Step 3: Verify status invariants**

Run:
```bash
docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c "
SELECT ls.status AS listing_status, bs.status AS booking_status, count(*)
FROM listings ls JOIN bookings bs ON bs.listing_id=ls.id
GROUP BY 1,2 ORDER BY 1,2;"
```
Expected pairs only: (ACTIVE,PENDING), (RESERVED,CONFIRMED), (SOLD,COMPLETED), (ACTIVE,CANCELLED). No other combination.

- [ ] **Step 4: Verify demo login**

Run:
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@demo.local","password":"Demo12345"}'
```
Expected: HTTP 200 with `accessToken` and `refreshToken` in JSON.

- [ ] **Step 5: Verify Testcontainers tests stay clean**

Run: `gradle test`
Expected: existing tests pass (no regressions from `006`, since `test` context skips it). If a test fails because it assumed an empty reference table that `005` now fills (breeds), investigate — `005` is safe but verify.

- [ ] **Step 6: Verify re-seed survival (down/up without -v)**

Run:
```bash
docker compose -f docker-compose.yml down
docker compose -f docker-compose.yml up -d
docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c "SELECT count(*) FROM users;"
```
Expected: 47 (data survived in `postgres-data` volume; Liquibase saw `006` already applied → no-op).

- [ ] **Step 7: Final commit (if any verification fixes were needed)**

If verification surfaced fixes, commit them. Otherwise no commit.

---

## Self-Review notes

- **Spec coverage:** breeds gap (Task 1), users/profiles (T2), listings/images (T3), bookings/reviews/ratings (T4), messages/favorites/subscriptions (T5), context wiring (T6), README (T7), verification incl. invariants + test protection + restart survival (T8). All spec sections mapped.
- **Volume adjustment:** Task 4 Step 2 re-balances counts from the spec's "~30 bookings / ~15 SOLD" to 40 bookings / 20 SOLD / 20 reviews (needed because `reviews.booking_id` is unique and each review needs a distinct COMPLETED booking). The spec said "~20 reviews" and "~30 bookings" with "~7 COMPLETED" — 7 COMPLETED cannot host 20 unique reviews. Plan resolves this explicitly rather than leaving an inconsistency. Listings total stays 100 (DRAFT dropped; spec had ~5 DRAFT as nice-to-have, YAGNI here to keep math clean).
- **Type/UUID consistency:** user `aaaaaaaa-...`, listing `bbbbbbbb-...`, image `cccccccc-...`, booking `dddddddd-...`, review `eeeeeeee-...`, message `12121212-...`, favorite `13131313-...`, subscription `14141414-...` — used consistently across tasks.
- **Placeholder handling:** generation specs for the 47/100/150/40/20/30/25/10 rows are written as explicit column templates + deterministic assignment rules, not literal every-row code (a 250-row literal plan is error-prone and unreadable). The implementer expands following the stated UUID schemes and assignment tables — this is complete specification, not hand-waving.