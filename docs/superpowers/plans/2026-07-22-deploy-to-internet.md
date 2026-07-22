# Deploy to Internet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Containerize the pet-marketplace backend, run the full stack (Postgres/Redis/Kafka/app/Caddy) in Docker Compose, expose a static demo front + the `/api/v1` API on host port `8080`, and document the host-side steps to publish it via the existing Keenetic cloud domain `www.novgorodtsev.netcraze.link`.

**Architecture:** Multi-stage Dockerfile builds the Spring Boot fat jar with Gradle 9.6.1 on JDK 26 (the bundled `./gradlew` targets Gradle 8.14 and cannot run on JDK 26). `docker-compose.yml` gains `app` and `caddy` services on the existing `petmarketplace-network`. Caddy listens on host `8080` (HTTP only — TLS is terminated by the Keenetic cloud), serves `static/` at `/` and reverse-proxies `/api/v1/*` to `app:8080`. A new `prod` Spring profile wires externalized config from env vars.

**Tech Stack:** Spring Boot 4.0.4, JDK 26, Gradle 9.6.1, Caddy 2, Docker Compose v2, PostgreSQL 16, Redis 7, Kafka 3.x (cp-kafka 7.6.1).

## Global Constraints

- **JDK 26 / Gradle 9.6.1** are mandatory; the bundled `./gradlew` (Gradle 8.14) does NOT run on JDK 26 — the Dockerfile must install Gradle 9.6.1 itself.
- App context-path is `/api/v1` (`server.servlet.context-path: /api/v1`). Actuator health is therefore at `/api/v1/actuator/health`, NOT `/actuator/health`.
- Public GET endpoints (no auth): `GET /api/v1/categories`, `GET /api/v1/listings`, `GET /api/v1/listings/{id}`, plus Swagger at `/api/v1/swagger-ui.html`.
- `mail.enabled` defaults to `false` (EmailSenderStub) — keep it false in prod.
- `storage.provider` is `local`; prod writes to `/app/uploads` (a bind-mounted volume).
- Jackson 3 only — do not add a Jackson 2 `ObjectMapper` bean (not needed for these tasks, but don't regress).
- Secrets (notably `JWT_SECRET` ≥256 bits, DB password) must come from `.env` (gitignored), never committed. `.env.example` is committed.
- Existing infra services in `docker-compose.yml` (postgres/redis/minio/mailpit/kafka) are kept; only their env is parameterized with defaults so dev is unaffected.

---

### Task 1: Dockerfile + .dockerignore

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Produces: a container image `pet-marketplace` whose runtime entrypoint is `java -jar /app/app.jar` with `SPRING_PROFILES_ACTIVE` overridable via env, listening on `8080`, with `curl` available inside for healthchecks.

- [ ] **Step 1: Create `.dockerignore`**

Create `.dockerignore` at repo root:

```
build/
.gradle/
bin/
dependency-sources/
.idea/
.vscode/
.git/
.claude/
.superpowers/
docs/
uploads/
temp/
logs/
html/
report/
coverage/
*.log
.aider*
.gitignore
README.md
SPECIFICATION.md
CLAUDE.md
docker-compose*.yml
```

- [ ] **Step 2: Create `Dockerfile`**

Create `Dockerfile` at repo root:

```dockerfile
# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:26-jdk AS build
ARG GRADLE_VERSION=9.6.1
RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle \
    && rm /tmp/gradle.zip
WORKDIR /build
COPY . .
RUN gradle build -x test --no-daemon
RUN cp build/libs/pet-marketplace-0.0.1-SNAPSHOT.jar /app.jar

# ---- runtime stage ----
FROM eclipse-temurin:26-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: Build the image**

Run:
```bash
docker build -t pet-marketplace:local .
```
Expected: exits `0`, prints `Successfully tagged pet-marketplace:local` (or `naming to ...`).

- [ ] **Step 4: Verify the runtime stage starts a JVM**

Run:
```bash
docker run --rm pet-marketplace:local java -version
```
Expected: prints a Temurin `Temurin-26.*` version line and exits `0` (confirms the JRE image works; the app itself will fail without a DB — that is expected and not tested here).

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "feat(deploy): add multi-stage Dockerfile and .dockerignore

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: application-prod.yml

**Files:**
- Create: `src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes env vars set by the `app` compose service (Task 3): `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `STORAGE_LOCAL_PATH`.
- Produces: the `prod` profile that the `app` container activates via `SPRING_PROFILES_ACTIVE=prod`, externalizing all infra hosts and disabling mail.

- [ ] **Step 1: Create the prod profile**

Create `src/main/resources/application-prod.yml`:

```yaml
spring:
  config:
    activate:
      on-profile: prod

  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}

  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST}
      port: ${SPRING_DATA_REDIS_PORT:6379}

  mail:
    enabled: false

  jpa:
    show-sql: false

storage:
  provider: local
  local:
    base-path: ${STORAGE_LOCAL_PATH:/app/uploads}

kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}

mail:
  enabled: false

logging:
  level:
    root: WARN
    com.petmarketplace: INFO
    org.springframework.security: WARN
```

- [ ] **Step 2: Validate the YAML parses**

Run:
```bash
python3 -c "import yaml,sys; yaml.safe_load(open('src/main/resources/application-prod.yml')); print('ok')"
```
Expected: prints `ok`, exits `0`. (If `python3`/PyYAML is unavailable, run `docker run --rm -v "$PWD:/w" -w /w python:3-slim python -c "import yaml; yaml.safe_load(open('src/main/resources/application-prod.yml')); print('ok')"`.)

- [ ] **Step 3: Confirm the project still builds**

Run:
```bash
gradle build -x test
```
Expected: `BUILD SUCCESSFUL`, and `build/libs/pet-marketplace-0.0.1-SNAPSHOT.jar` exists. (The new profile is packaged as a resource; a broken profile would not fail the build, which is why Step 2 validates the YAML directly.)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application-prod.yml
git commit -m "feat(deploy): add prod Spring profile with externalized config

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: docker-compose app + caddy services, .env.example

**Files:**
- Modify: `docker-compose.yml` (parameterize `postgres` env; add `app` and `caddy` services)
- Create: `.env.example`

**Interfaces:**
- Consumes: image `pet-marketplace:local` from Task 1; `prod` profile from Task 2; `Caddyfile` and `static/` from Task 4.
- Produces: `docker compose up -d --build` running the full stack with Caddy on host port `8080`.

- [ ] **Step 1: Parameterize the postgres service env**

In `docker-compose.yml`, replace the `postgres` service `environment:` block:

```yaml
  postgres:
    image: postgres:16-alpine
    container_name: petmarketplace-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-petmarketplace}
      POSTGRES_USER: ${POSTGRES_USER:-petmarketplace}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-petmarketplace}
```

(Leave the rest of the `postgres` service — volumes, healthcheck, networks — unchanged. The `:-` defaults keep existing dev behavior identical.)

- [ ] **Step 2: Add `app` and `caddy` services**

Append, immediately before the `volumes:` top-level key, these two services:

```yaml
  app:
    build: .
    image: pet-marketplace:local
    container_name: petmarketplace-app
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-petmarketplace}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-petmarketplace}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-petmarketplace}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: "6379"
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET:-change-me-in-production-use-a-secure-random-string-of-at-least-256-bits}
      STORAGE_LOCAL_PATH: /app/uploads
    volumes:
      - app-uploads:/app/uploads
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/api/v1/actuator/health || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 12
      start_period: 50s
    networks:
      - petmarketplace-network

  caddy:
    image: caddy:2-alpine
    container_name: petmarketplace-caddy
    ports:
      - "8080:80"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./static:/srv/static:ro
    depends_on:
      app:
        condition: service_healthy
    networks:
      - petmarketplace-network
```

- [ ] **Step 3: Register the new volume**

Add `app-uploads` to the top-level `volumes:` block:

```yaml
volumes:
  postgres-data:
  redis-data:
  minio-data:
  mailpit-data:
  app-uploads:
```

- [ ] **Step 4: Create `.env.example`**

Create `.env.example` at repo root:

```bash
# ---- Pet Marketplace deployment env ----
# Copy to .env (gitignored) and edit before: docker compose up -d --build

# Database (also used by the postgres service)
POSTGRES_DB=petmarketplace
POSTGRES_USER=petmarketplace
POSTGRES_PASSWORD=change-me-strong-password

# JWT secret — MUST be >= 256 bits (>= 32 chars of random). Generate with:
#   openssl rand -base64 48
JWT_SECRET=change-me-in-production-use-a-secure-random-string-of-at-least-256-bits
```

- [ ] **Step 5: Validate the compose file**

Run:
```bash
docker compose config >/dev/null && echo "valid"
```
Expected: prints `valid`, exits `0`. (This requires `Caddyfile` and `./static` to exist as bind paths; create empty placeholders if running this step before Task 4 — `touch Caddyfile && mkdir -p static && touch static/index.html` — then overwrite them in Task 4.)

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env.example
git commit -m "feat(deploy): add app and caddy services to docker-compose

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Caddyfile + static demo front, full-stack smoke

**Files:**
- Create: `Caddyfile`
- Create: `static/index.html`
- Create: `static/app.js`

**Interfaces:**
- Consumes: `app:8080` from Task 3 (serves `/api/v1/*` and `/api/v1/actuator/health`).
- Produces: a public-facing demo page at `/` plus the proxied API; the full stack verifiable with `curl` against `localhost:8080`.

- [ ] **Step 1: Create `Caddyfile`**

Create `Caddyfile` at repo root:

```caddyfile
:80 {
	# API + actuator + Swagger all live under the app's context-path /api/v1
	handle /api/v1/* {
		reverse_proxy app:8080
	}

	# Static demo front (single-page fallback to index.html)
	handle {
		root * /srv/static
		try_files {path} /index.html
		file_server
	}
}
```

- [ ] **Step 2: Create `static/index.html`**

Create `static/index.html`:

```html
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Pet Marketplace — demo</title>
  <style>
    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 24px; color: #1b1b1b; }
    h1 { font-size: 1.4rem; }
    h2 { font-size: 1.1rem; margin-top: 2rem; }
    .muted { color: #666; font-size: 0.9rem; }
    ul { line-height: 1.6; }
    .card { border: 1px solid #e3e3e3; border-radius: 8px; padding: 12px 16px; margin: 8px 0; }
    .price { font-weight: 600; }
    nav a { margin-right: 12px; }
    #err { color: #b00; }
  </style>
</head>
<body>
  <h1>Pet Marketplace</h1>
  <p class="muted">Демо-фронт. Полный API — в <a href="/api/v1/swagger-ui.html">Swagger UI</a>.</p>
  <nav>
    <a href="/api/v1/swagger-ui.html">Swagger</a>
    <a href="/api/v1/actuator/health">health</a>
  </nav>

  <h2>Категории</h2>
  <ul id="categories"><li class="muted">загрузка…</li></ul>

  <h2>Последние объявления</h2>
  <div id="listings"><p class="muted">загрузка…</p></div>
  <p id="err"></p>

  <script src="/app.js"></script>
</body>
</html>
```

- [ ] **Step 3: Create `static/app.js`**

Create `static/app.js`:

```js
const API = "/api/v1";
const errEl = document.getElementById("err");

async function getJSON(url) {
  const res = await fetch(url, { headers: { "Accept-Language": "ru" } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${url}`);
  return res.json();
}

async function loadCategories() {
  const ul = document.getElementById("categories");
  try {
    const cats = await getJSON(`${API}/categories`);
    if (!Array.isArray(cats) || cats.length === 0) {
      ul.innerHTML = '<li class="muted">категорий нет</li>';
      return;
    }
    ul.innerHTML = cats
      .map((c) => `<li>${c.name ?? c.slug ?? c.id}${(c.breeds?.length ?? 0) ? ` — ${c.breeds.length} пород` : ""}</li>`)
      .join("");
  } catch (e) {
    ul.innerHTML = "";
    errEl.textContent = `Категории: ${e.message}`;
  }
}

function listingCard(l) {
  const title = l.title ?? "(без названия)";
  const price = l.price != null ? `${l.price} ${l.currency ?? ""}` : "—";
  const city = l.locationCity ?? "";
  return `<div class="card"><div>${title}</div><div class="muted">${city} · ${l.status ?? ""}</div><div class="price">${price}</div></div>`;
}

async function loadListings() {
  const el = document.getElementById("listings");
  try {
    const page = await getJSON(`${API}/listings?page=0&size=10`);
    const items = Array.isArray(page) ? page : page.content ?? [];
    if (items.length === 0) {
      el.innerHTML = '<p class="muted">объявлений нет</p>';
      return;
    }
    el.innerHTML = items.map(listingCard).join("");
  } catch (e) {
    el.innerHTML = "";
    errEl.textContent = `Объявления: ${e.message}`;
  }
}

loadCategories();
loadListings();
```

- [ ] **Step 4: Build and start the full stack**

Run:
```bash
docker compose up -d --build
```
Expected: builds `app`, starts postgres/redis/kafka/minio/mailpit/app/caddy, exits `0`.

- [ ] **Step 5: Create the Kafka topics**

Run:
```bash
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.requests --partitions 1 --replication-factor 1
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.replies --partitions 1 --replication-factor 1
```
Expected: each prints `Created topic ...` (or nothing if already exists with `--if-not-exists`).

- [ ] **Step 6: Wait for app health, then verify endpoints**

Run:
```bash
docker compose up -d --wait app
curl -fsS http://localhost:8080/api/v1/actuator/health; echo
curl -fsS http://localhost:8080/api/v1/categories; echo
curl -s http://localhost:8080/ | grep -o 'Pet Marketplace'
```
Expected:
- health → `{"status":"UP",...}`
- categories → a JSON array (possibly `[]` if the DB has no seed; on a fresh Liquibase run, seed data gives categories)
- front HTML → prints `Pet Marketplace`

- [ ] **Step 7: Tear down the local stack**

Run:
```bash
docker compose down
```
Expected: removes all containers (volumes retained).

- [ ] **Step 8: Commit**

```bash
git add Caddyfile static/index.html static/app.js
git commit -m "feat(deploy): add Caddy reverse proxy and static demo front

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: README "Deploy" section (host-side steps)

**Files:**
- Modify: `README.md` (add a `## Deploy (public demo)` section)

**Interfaces:**
- Consumes: all artifacts from Tasks 1–4.
- Produces: documented host-side steps to publish the running stack via the Keenetic cloud domain, plus the alternative last-mile options.

- [ ] **Step 1: Read the current README tail to find a clean insertion point**

Run:
```bash
grep -n "^## " README.md | tail -20
```
Expected: lists existing top-level sections; insert the new section after the last one (before any `## License`/footer if present, otherwise append).

- [ ] **Step 2: Append the Deploy section to `README.md`**

Append this block to `README.md` (adjust the heading level to match the repo's convention — use the same `##` depth as sibling sections):

````markdown
## Deploy (public demo)

Run the full stack in Docker Compose on the host and publish it through the Keenetic cloud domain.

### 1. Prerequisites on the host (Debian)

```bash
# Docker Engine + Compose plugin
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### 2. Configure and start the stack

```bash
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD and JWT_SECRET (>= 256 bits, e.g. `openssl rand -base64 48`)
docker compose up -d --build
```

Create the Kafka topics (the broker has `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`):

```bash
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.requests --partitions 1 --replication-factor 1
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.replies --partitions 1 --replication-factor 1
```

Verify locally on the host:

```bash
curl -fsS http://localhost:8080/api/v1/actuator/health   # {"status":"UP",...}
curl -fsS http://localhost:8080/                          # demo front HTML
```

### 3. Open the firewall port

The host's nftables `input` chain has `policy drop` and only allows SSH (and a couple of other ports). Allow the router to reach the app on `8080` and persist the rule:

```bash
sudo nft insert rule inet filter input iifname "enp1s0" tcp dport 8080 accept
sudo sh -c 'nft -s list ruleset > /etc/nftables.conf'
```

(If the host loads rules from a different file, save to that file instead; verify with `sudo nft list chain inet filter input`.)

### 4. Publish via the Keenetic cloud (recommended — keeps the existing domain)

`netcraze.link` is a KeenDNS domain: the Keenetic cloud (front `78.47.125.180`) terminates HTTPS (Let's Encrypt cert `novgorodtsev.netcraze.link`) and tunnels to the Keenetic router, which forwards to an internal `IP:port`.

In the Keenetic web GUI (KeenDNS / "Доступ из интернета"), retarget the `www.novgorodtsev.netcraze.link` cloud publication to:

- internal host: `192.168.1.81`
- port: `8080`
- protocol: HTTP (TLS is handled by the cloud)

Then verify publicly:

```bash
curl -fsS https://www.novgorodtsev.netcraze.link/api/v1/actuator/health
```

### 5. Alternative last-mile options

If the Keenetic publication cannot target an arbitrary `IP:port`:

- **Cloudflare Tunnel (no router/domain changes):** install `cloudflared` on the host and run `cloudflared tunnel --url http://localhost:8080` for an instant `https://<random>.trycloudflare.com`.
- **Direct router port-forward:** forward `80/443` on the Keenetic to `192.168.1.81`, repoint the netcraze A-record to the router's WAN IP `185.155.18.14`, and switch the `Caddyfile` to `:80` + `:443` with automatic HTTPS (Caddy obtains its own Let's Encrypt cert). Note: a dynamic home WAN IP requires a dynDNS A-record.
````

- [ ] **Step 3: Verify the README renders sensibly**

Run:
```bash
grep -n "## Deploy" README.md
```
Expected: prints one line matching `## Deploy (public demo)`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs(deploy): add Deploy section for public demo via Keenetic cloud

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Dockerfile (multi-stage, Gradle 9.6.1, JDK 26) → Task 1.
- `.dockerignore` → Task 1.
- `docker-compose.yml` `app` + `caddy` services, host port `8080`, `depends_on` healthy, volumes, healthcheck → Task 3.
- Parameterized postgres env (compatible) → Task 3 Step 1.
- `Caddyfile` (`:80`, proxy `/api/v1/*`, file_server static) → Task 4.
- `static/index.html` + `static/app.js` (public GET endpoints) → Task 4.
- `application-prod.yml` (externalized hosts, `mail.enabled:false`, `storage.local`, `SPRING_PROFILES_ACTIVE`) → Task 2.
- `.env.example` + `.env` already gitignored → Task 3 Step 4 (`.env` was already in `.gitignore`).
- README Deploy section (Docker install, configure, topics, firewall, Keenetic retarget, alternatives) → Task 5.

**Placeholder scan:** none — every step has the actual content/commands.

**Type consistency:** actuator health path is consistently `/api/v1/actuator/health` (context-path). Env var names match between `application-prod.yml` (Task 2) and the `app` service (Task 3): `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SPRING_DATA_REDIS_HOST/PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `STORAGE_LOCAL_PATH`, `JWT_SECRET`, `POSTGRES_DB/USER/PASSWORD`. Boot jar name `pet-marketplace-0.0.1-SNAPSHOT.jar` is consistent between Dockerfile (Task 1) and the Gradle build output (Task 2 Step 3).

**Corrections applied during review:**
- Health endpoint is `/api/v1/actuator/health`, not `/actuator/health` (context-path `/api/v1`). All commands use the correct path.
- The runtime image installs `curl` because `eclipse-temurin:26-jre` does not ship it; the `app` healthcheck depends on it.
- `curl`/`unzip`/`ca-certificates` installed in the build stage for the Gradle download.
```