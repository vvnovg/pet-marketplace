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

Integration tests use Testcontainers to spin up PostgreSQL and Redis automatically.

```bash
gradle test
```

To run a single test class:

```bash
gradle test --tests "com.petmarketplace.application.auth.controller.AuthControllerTest"
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

These variables can be exported or placed in an `application-local.yml` / `.env` file and referenced via Spring configuration.
