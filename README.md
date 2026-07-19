# PetMarketplace Backend

Backend REST API for a pet marketplace. Connects sellers (breeders, shelters, private individuals) with buyers, providing listing publication, search, bookings, internal messaging, reviews and moderation tools.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 26 |
| Framework | Spring Boot 4.x |
| ORM | Spring Data JPA (Hibernate) |
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| Security | Spring Security + JWT (access/refresh tokens) |
| API Docs | OpenAPI 3 + SpringDoc |
| Build | Gradle (Kotlin DSL) |
| Migrations | Liquibase |
| Tests | JUnit 5, Mockito, Testcontainers |
| Containerization | Docker + Docker Compose |

## Project Structure

The application follows a layered architecture:

- `application` — REST controllers, DTOs, mappers and services
- `domain` — JPA entities, enums and Spring Data repositories
- `infrastructure` — security, file storage, email notifications and configuration

Main modules: auth, users, listings, categories, bookings, messages, reviews, favorites, subscriptions and admin.

## How to Run Locally

### Prerequisites

- JDK 26
- Docker + Docker Compose
- Gradle 9 (system `gradle`; the bundled `./gradlew` wrapper targets Gradle 8.14 and cannot run on JDK 26)

### 1. Start infrastructure services

```bash
docker-compose up -d
```

This starts PostgreSQL, Redis, MinIO and Mailpit.

### 2. Run the application

```bash
gradle bootRun
```

The API is available at `http://localhost:8080/api/v1`.

### 3. Stop infrastructure

```bash
docker-compose down
```

To remove data volumes as well:

```bash
docker-compose down -v
```

## How to Run Tests

The same integration suite (72 tests) runs in two modes.

### Embedded (Testcontainers) — default

Spins up PostgreSQL and Redis in Docker automatically. Requires Docker.

```bash
gradle test
```

To run a single test class:

```bash
gradle test --tests "com.petmarketplace.application.auth.controller.AuthControllerTest"
```

### Against an external stand — `testOnStand`

Runs the tests against an already-running instance of the app (local `docker-compose up -d && gradle bootRun`, or a remote stand) instead of Testcontainers. No Docker required on the test machine. The test JVM connects to the stand's database and Redis and drives the stand's HTTP API; test data is seeded before each test class and cleaned up after.

```bash
# 1. start the stand (in one terminal)
docker-compose up -d
gradle bootRun

# 2. run the tests against it (in another terminal)
gradle testOnStand
```

By default this targets a stand at `http://localhost:8080/api/v1` with the local compose database. For a remote stand, override via env vars (the `STAND_JWT_SECRET` must match the stand's `JWT_SECRET` so tokens minted by the tests validate on the stand):

```bash
STAND_BASE_URL=http://stand.example.com/api/v1 \
STAND_DB_URL=jdbc:postgresql://stand.example.com:5432/petmarketplace \
STAND_DB_USER=petmarketplace \
STAND_DB_PASSWORD=petmarketplace \
STAND_REDIS_HOST=stand.example.com \
STAND_JWT_SECRET=<the stand's JWT secret> \
gradle testOnStand
```

## API Documentation

Interactive Swagger UI is available after starting the application:

```
http://localhost:8080/api/v1/swagger-ui.html
```

OpenAPI JSON contract:

```
http://localhost:8080/api/v1/v3/api-docs
```

Protected endpoints require a JWT bearer token. Use `/auth/login` or `/auth/register` + `/auth/verify-email` to obtain one.

## Authentication

Access tokens (JWT, HS256) are valid for **15 minutes**; refresh tokens are valid for **7 days** and rotate on use via `POST /auth/refresh`. Send the access token as `Authorization: Bearer <token>` (or use the **Authorize** button in Swagger UI).

### Obtaining a token on the stand

There is no pre-seeded user — create one and log in. Registration creates a `BUYER` with `is_verified = false`; login rejects unverified accounts, so the email must be verified first.

```bash
# 1. register (creates an unverified BUYER)
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"buyer@stand.local","password":"Password1!","firstName":"Buyer","lastName":"Stand"}'

# 2. verify — with MAIL_ENABLED=false (default) no email is sent; the verify token is in Redis.
TOKEN=$(docker exec petmarketplace-redis redis-cli --scan --pattern 'verify:*' \
  | while read k; do v=$(docker exec petmarketplace-redis redis-cli GET "$k"); \
     [ "$v" = "buyer@stand.local" ] && echo "${k#verify:}"; done | head -1)
curl -X POST "http://localhost:8080/api/v1/auth/verify-email?token=$TOKEN"

# 3. login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"buyer@stand.local","password":"Password1!"}'
```

For quick local testing you can skip the email round-trip and flip the flag directly:

```bash
docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -c \
  "update users set is_verified = true where email = 'buyer@stand.local';"
```

### Testing role-restricted endpoints (`/admin/**`)

Public registration only creates `BUYER` accounts (`AuthService.register` hardcodes the role), so `SELLER`, `ADMIN` and `MODERATOR` users must be provisioned directly in the database. The snippet below reuses an existing user's bcrypt hash so the same password works, then you log in to get a token for that role:

```bash
psqlq() { docker exec petmarketplace-postgres psql -U petmarketplace -d petmarketplace -tA "$@"; }
HASH=$(psqlq -c "select password_hash from users where email='buyer@stand.local';")
for ROLE in SELLER ADMIN MODERATOR; do
  EMAIL="${ROLE,,}@stand.local"
  psqlq -c "insert into users (id, email, first_name, last_name, password_hash, role, is_active, is_verified, created_at, updated_at)
            values (gen_random_uuid(), '$EMAIL', '$ROLE', 'Stand', '$HASH', '$ROLE', true, true, now(), now());"
  UID=$(psqlq -c "select id from users where email='$EMAIL';")
  psqlq -c "insert into profiles (id, user_id, country, city, rating, total_reviews, created_at)
            values (gen_random_uuid(), '$UID', 'Russia', 'Moscow', 0, 0, now()) on conflict do nothing;"
done

# then log in as the role you need:
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@stand.local","password":"Password1!"}'
```

The stand uses the dev default JWT secret (`change-me-in-production-use-a-secure-random-string-of-at-least-256-bits`) unless `JWT_SECRET` is set — tokens minted by the stand validate only against that same secret.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | `change-me-in-production-use-a-secure-random-string-of-at-least-256-bits` | Secret for signing JWT tokens. Must be at least 256 bits in production. |
| `JWT_ACCESS_EXPIRATION_MINUTES` | `15` | Access token lifetime in minutes |
| `JWT_REFRESH_EXPIRATION_DAYS` | `7` | Refresh token lifetime in days |
| `STORAGE_LOCAL_PATH` | `./uploads` | Base path for local file storage |
| `STORAGE_PROVIDER` | `local` | Storage provider: `local` or `minio` |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO endpoint URL |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO access key |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO secret key |
| `MINIO_BUCKET_NAME` | `petmarketplace` | Default MinIO bucket |
| `MAIL_ENABLED` | `false` | Enable real SMTP sending. When `false`, emails are logged only. |
| `MAIL_HOST` | `localhost` | SMTP host |
| `MAIL_PORT` | `1025` | SMTP port |
| `MAIL_USERNAME` | `` | SMTP username |
| `MAIL_PASSWORD` | `` | SMTP password |

### Stand test mode (`testOnStand`)

| Variable | Default | Description |
|----------|---------|-------------|
| `STAND_BASE_URL` | `http://localhost:8080/api/v1` | Base URL the tests drive via HTTP |
| `STAND_DB_URL` | `jdbc:postgresql://localhost:5432/petmarketplace` | JDBC URL of the stand's database |
| `STAND_DB_USER` | `petmarketplace` | Stand database username |
| `STAND_DB_PASSWORD` | `petmarketplace` | Stand database password |
| `STAND_REDIS_HOST` | `localhost` | Stand Redis host |
| `STAND_REDIS_PORT` | `6379` | Stand Redis port |
| `STAND_JWT_SECRET` | the dev default secret | Must match the stand's `JWT_SECRET` |

These variables can be exported or placed in an `application-local.yml` / `.env` file and referenced via Spring configuration.
