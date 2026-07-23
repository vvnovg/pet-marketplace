# DB Demo Data Seeding — Design

**Date:** 2026-07-23
**Goal:** Populate the production demo stack (Docker Compose, `prod` profile) with realistic data so the frontend distribution renders a live marketplace. Data must survive `docker compose down/up` (without `-v`).

## Decisions

| Decision | Value |
|---|---|
| Target environment | Prod demo (Docker Compose) |
| Volume | Medium (~250 rows: ~47 users, ~100 listings, ~30 bookings, ~20 reviews, ~30 messages, ~25 favorites, ~10 subscriptions, ~100 listing images) |
| Approach | A — Liquibase seed changesets (declarative, idempotent, survives restarts) |
| Demo logins | Explicit accounts with known email/password `Demo12345` (one shared BCrypt hash), `is_verified=true` |
| Reference data | Fill missing breeds for `reptiles`/`fish`/`other` categories |
| Test mitigation | Liquibase `contextFilter` excludes `test` profile → Testcontainers tests keep a clean DB |

## Architecture

Two new Liquibase changeset files included from `db.changelog-master.yaml` after `001-init-schema.yaml`:

### `002-seed-breeds-gap.yaml`
Closes the reference-data gap. Currently `001` seeds breeds only for `dogs`, `cats`, `birds`, `rodents`. Add 2-3 breeds each for `reptiles` (category `55555555-5555-5555-5555-555555555555`), `fish` (`66666666-6666-6666-6666-666666666666`), `other` (`77777777-7777-7777-7777-777777777777`). Deterministic breed UUIDs following the `001` pattern (e.g. `55500000-0000-0000-0000-000000000001`). Idempotent per-row via `preConditions` `sqlCheck SELECT count(*) FROM breeds WHERE id = ...` with `onFail: MARK_RAN`. Safe under any context.

### `003-seed-demo-data.yaml`
Main demo data. One changeset wrapped in `preConditions`:
- `sqlCheck SELECT count(*) FROM users` → if `> 0`, `onFail: MARK_RAN` (never overwrite an already-populated DB; idempotent across `down/up`; on `down -v` + `up` it re-runs and restores data).
- `contextFilter: "prod or dev or stand"` (excludes `test` → Testcontainers stays clean).

Insertion order follows the FK graph (NOT NULL FKs first):

1. **users** (~47 rows): `admin@demo.local`, `moderator@demo.local`, `seller1..5@demo.local`, `buyer@demo.local` (explicit demo accounts), then `seller6..15@demo.local`, `buyer1..29@demo.local`. Domain is `@demo.local` — **not** `@example.com` (the stand tests delete `LIKE '%@example.com'`, so no collision). Columns: `email`, `password_hash` (one shared BCrypt hash of `Demo12345`, YAML literal), `role` (BUYER/SELLER/ADMIN/MODERATOR enum-string), `first_name`, `last_name`, `is_verified=true`, `is_active=true`.
2. **profiles** (~47 rows, 1:1): `user_id`, `country`/`city` (spread across ~6 RU cities), `bio`, `rating=0.0`, `total_reviews=0` (updated for sellers with approved reviews via a trailing `UPDATE`).
3. **listings** (~100 rows): `seller_id` ∈ sellers, `category_id`/`breed_id` ∈ reference (incl. new breeds), `status` distribution ~60 ACTIVE / ~10 RESERVED / ~15 SOLD / ~10 PENDING_MODERATION / ~5 DRAFT, `price`, `gender`, `age_months`, `location_country/city`, `views_count` (random-ish literal), `has_vaccination`/`has_documents`.
4. **listing_images** (~100 rows, one per listing): `url` is a root-relative path `/animals/<breed-slug>.<ext>` pointing at a real breed photo (CC0/PD/CC-BY, sourced from Wikimedia Commons) bundled with the frontend distribution at `public/animals/`. Root-relative so it resolves through the public frontend `:3000` (the backend `:8080` is firewall-closed in production); `order_index=0`, `is_main=true`. Attributions live in the frontend repo at `public/animals/ATTRIBUTIONS.md`.
5. **bookings** (~30 rows): `buyer_id`/`seller_id`/`listing_id`, `status` ~10 PENDING / ~8 CONFIRMED / ~7 COMPLETED / ~5 CANCELLED. Status is paired with listing status (see Invariants).
6. **reviews** (~20 rows): only on COMPLETED bookings (unique `booking_id`), `author_id`/`recipient_id`/`rating` (1-5, CHECK), `comment`, `status` ~15 APPROVED / ~5 PENDING.
7. **messages** (~30 rows, 5-6 chats): `sender_id`/`receiver_id`/`listing_id`/`content`/`is_read`, `created_at` incremental.
8. **favorites** (~25 rows): unique (user_id, listing_id).
9. **subscriptions** (~10 rows): `filters` JSONB as a string literal, e.g. `{"categoryId":"11111111-1111-1111-1111-111111111111","city":"Москва","maxPrice":50000}`, `is_active=true`.

Trailing `UPDATE profiles SET rating=..., total_reviews=...` for sellers that have approved reviews, so the computed rating fields are consistent with the seeded reviews.

## Invariants (status consistency)

The app maintains invariants between `listing.status` and `booking.status`:
- `booking CONFIRMED` ⇒ `listing RESERVED`
- `booking COMPLETED` ⇒ `listing SOLD`
- `booking CANCELLED` (from CONFIRMED) ⇒ `listing ACTIVE`

The seed sets statuses **in explicit pairs** so the data is consistent with what the app would have left. DRAFT / PENDING_MODERATION listings have no bookings. The reserved/sold listings are exactly the ones referenced by CONFIRMED / COMPLETED bookings.

## BCrypt password

One shared hash for all demo users, hardcoded as a YAML literal. Generated from `Demo12345` via BCrypt strength 10 (matches `SecurityConfig`'s `BCryptPasswordEncoder`). `is_verified=true` so `/auth/login` works without email confirmation. Documented in README.

## Idempotency & restart survival

- `003`: `preConditions` `count(users) == 0` else `MARK_RAN`. First `up` on empty DB → seeds. `down` (no `-v`) + `up` → DB kept in `postgres-data` volume, Liquibase sees `003` applied → no-op. `down -v` + `up` → volume wiped → `003` re-runs, data restored.
- `002`: per-row `sqlCheck` idempotency.

## Liquibase context wiring

Add `liquibase.contexts` to profile configs so the `contextFilter` on `003` actually filters:
- `application.yml` (dev/default): `liquibase.contexts: dev`
- `application-prod.yml`: `liquibase.contexts: prod`
- `src/test/resources/application-stand.yml`: `liquibase.contexts: stand` (note: stand currently sets `liquibase.enabled: false`; the context is set but stand owns its schema — the `stand` context matters only if stand-mode Liquibase is re-enabled; harmless to declare now).
- `src/test/resources/application-test.yml`: `liquibase.contexts: test` (explicitly excludes `prod/dev/stand`, so `003` is skipped on Testcontainers → clean test DB).

`002` (breeds) has no `contextFilter` — runs everywhere, safe.

## Files changed

- `src/main/resources/db/changelog/db.changelog-master.yaml` — add `include` for `002` and `003`.
- `src/main/resources/db/changelog/changelogs/002-seed-breeds-gap.yaml` — new.
- `src/main/resources/db/changelog/changelogs/003-seed-demo-data.yaml` — new.
- `src/main/resources/application.yml` — add `liquibase.contexts: dev`.
- `src/main/resources/application-prod.yml` — add `liquibase.contexts: prod`.
- `src/test/resources/application-stand.yml` — add `liquibase.contexts: stand`.
- `src/test/resources/application-test.yml` — add `liquibase.contexts: test`.
- `README.md` — "Demo data" section: demo account list, password `Demo12345`, how to re-seed (`docker compose down -v && docker compose up -d --build`), note that Testcontainers tests stay clean.

## Verification

- `gradle build -x test` — changeset YAML validates.
- `docker compose -f docker-compose.yml up -d --build` → `curl http://localhost:8080/api/v1/actuator/health` UP.
- Row counts: `SELECT count(*) FROM users/listings/bookings/reviews/messages/favorites/subscriptions;` match expectations.
- Login: `POST /api/v1/auth/login` with `admin@demo.local` / `Demo12345` → access token returned.
- Invariant check: `SELECT ls.status, bs.status, count(*) FROM listings ls JOIN bookings bs ON bs.listing_id=ls.id GROUP BY 1,2;` — pairs must be consistent (CONFIRMED↔RESERVED, COMPLETED↔SOLD, CANCELLED↔ACTIVE, PENDING↔ACTIVE).
- `gradle test` — Testcontainers tests pass (clean DB, `003` skipped via `test` context).

## Out of scope

- Faker/random data generation (static YAML is sufficient for demo).
- Re-seeding into a partially-populated DB (the `count(users)==0` guard refuses this by design — explicit `down -v` to reseed).
- Backfill of `@example.com` test-domain coordination (we use `@demo.local` to stay clear).