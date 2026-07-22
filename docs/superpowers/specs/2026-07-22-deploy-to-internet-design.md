# Deploy to Internet — Design Spec

Date: 2026-07-22
Status: Approved (pending implementation plan)

## Goal

Publish the pet-marketplace backend as a demonstrable "site" on the public internet, from a home server behind a Keenetic router, reusing the existing `www.novgorodtsev.netcraze.link` domain and its HTTPS certificate.

## Current topology (discovered)

- **Home box** `debian`: LAN IP `192.168.1.81`, interface `enp1s0`, default route via Keenetic router `192.168.1.1`. Public IPv4 via the router: `185.155.18.14` (router's static WAN IP). No public IPv6.
- Running on the box: `apache2` on `:80` only (serves static CV files from `/var/www/www.novgorodtsev.de`; `mod_ssl` not active, nothing on `:443`). `nginx` installed but cannot start (apache holds `:80`).
- Docker is **not** installed.
- `awg`/`awg-quick` installed but disabled/not running. No other tunnel/ddns client (`cloudflared`, `frp`, `ngrok`, `tailscale`, netcraze client) present.
- **Domain** `www.novgorodtsev.netcraze.link` → `78.47.125.180` (Hetzner). `netcraze.link` is a **KeenDNS (Keenetic cloud) domain**: the Keenetic cloud front at `78.47.125.180` terminates TLS (Let's Encrypt cert, `CN=novgorodtsev.netcraze.link`, valid until 2026-09-18) and tunnels to the user's Keenetic router, which forwards to an internal target.
  - `novgorodtsev.netcraze.link` (apex) → Keenetic router GUI (works).
  - `www.novgorodtsev.netcraze.link` → configured in the router as an HTTPS cloud publication pointing at an internal target → currently `502 Bad Gateway`.
- **Firewall (root cause of the 502):** nftables `inet filter` `input` chain has `policy drop` and only accepts `iif lo`, icmp, conntrack established/related, `enp1s0 tcp dport 22`, `enp1s0 udp dport 52379`; everything else is `reject`. **Ports 80/443/8080 are not accepted on `enp1s0`**, so the router's forwarded HTTP to `192.168.1.81` is rejected → the Keenetic cloud front returns `502`. Apache itself is healthy (local `curl http://localhost/` → `200`).

## Design principle

The repository artifacts are **exposure-agnostic**: the stack runs in Docker Compose and is exposed on `localhost:8080` (host) of the Debian box. The "last mile" to the public internet reuses the existing Keenetic cloud + `www.novgorodtsev.netcraze.link` domain, by retargeting the router's cloud publication to `192.168.1.81:8080` (HTTP) and opening that port in the host firewall.

## Approach chosen

Keenetic cloud publication (reuse existing domain + cert). Cloud terminates TLS; router forwards plain HTTP to `192.168.1.81:8080`; Caddy serves the static front and proxies `/api/v1` to the app.

Rejected/alternative last-mile options (documented, not the default):
- Cloudflare Tunnel (instant `https://<random>.trycloudflare.com`) — used only if the Keenetic retarget cannot be made to point at an arbitrary `IP:port`.
- Direct router port-forwarding `80/443` → `192.168.1.81` + repoint DNS to `185.155.18.14` + Caddy auto-HTTPS — more moving parts, requires DNS change.

## Artifacts to add/change in the repository

1. **`Dockerfile`** (multi-stage):
   - Build stage: `eclipse-temurin:26-jdk`, install Gradle 9.6.1 (do **not** use the bundled `./gradlew` — it targets Gradle 8.14 and cannot run on JDK 26), run `gradle build -x test`, copy the boot jar from `build/libs`.
   - Runtime stage: `eclipse-temurin:26-jre`, copy the jar, `EXPOSE 8080`, `ENTRYPOINT` runs the jar with `SPRING_PROFILES_ACTIVE` from env.
2. **`.dockerignore`**: exclude `build/`, `.gradle/`, `.idea/`, `uploads/`, `dependency-sources/`, `.aider*`, `.git/`, `docs/`, `bin/`, `dependency-sources/`.
3. **`docker-compose.yml` — additions** (existing infra services unchanged):
   - `app` service: `build: .`, `depends_on` (postgres/redis/kafka with `condition: service_healthy`), env from `.env`, healthcheck `GET /actuator/health`, volume `./uploads:/app/uploads`, on `petmarketplace-network`.
   - `caddy` service: image `caddy:2-alpine`, host port mapping `8080:80`, volumes for `Caddyfile` (ro) and `./static:/srv/static` (ro), on the same network.
4. **`Caddyfile`** (repo root):
   - `:80` listener (HTTP only — TLS is terminated by the Keenetic cloud front).
   - `reverse_proxy /api/v1/* -> app:8080`.
   - `handle /* -> file_server root /srv/static`.
   - Swagger UI is under `/api/v1/swagger-ui.html`, so it is covered by the `/api/v1/*` proxy.
5. **`static/index.html`** (+ `static/app.js`):
   - Minimal, framework-free page. On load, fetch public `GET /api/v1/categories` and `GET /api/v1/listings`, render categories + recent active listings.
   - Link to `/api/v1/swagger-ui.html` for the full API.
6. **`application-prod.yml`** (under `src/main/resources/`):
   - `spring.datasource.url` ← `${SPRING_DATASOURCE_URL}`, user/password from env.
   - `spring.data.redis.host` ← `${SPRING_DATA_REDIS_HOST}` (= service name `redis`), port from env.
   - `kafka.bootstrap-servers` ← `${KAFKA_BOOTSTRAP_SERVERS}` (= `kafka:9092`).
   - `mail.enabled: false`.
   - `storage.provider: local`, `storage.local.base-path: /app/uploads`.
   - Activated via `SPRING_PROFILES_ACTIVE=prod`.
7. **`.env.example`** (committed) and **`.env`** (gitignored):
   - `JWT_SECRET` (≥256 bits), `POSTGRES_DB/USER/PASSWORD`, `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP`, `SPRING_PROFILES_ACTIVE=prod`.
   - Add `.env` to `.gitignore`.
8. **README "Deploy" section** — step-by-step:
   1. Install Docker Engine + Compose on the Debian box.
   2. `cp .env.example .env`, edit secrets (`JWT_SECRET`, DB password).
   3. `docker compose up -d --build`.
   4. Create Kafka topics (commands from CLAUDE.md).
   5. Verify locally: `curl http://localhost:8080/actuator/health` → `UP`; `http://localhost:8080/`; `http://localhost:8080/api/v1/swagger-ui.html`.
   6. **Firewall:** allow `enp1s0 tcp dport 8080` in nftables and persist (e.g. add a rule to the `input` chain, save with `nft list ruleset > /etc/nftables.conf` or the host's persist mechanism).
   7. **Keenetic:** in the web GUI, retarget the `www.novgorodtsev.netcraze.link` cloud publication to `192.168.1.81:8080` (HTTP).
   8. Verify public: `https://www.novgorodtsev.netcraze.link/` (front), `/api/v1/categories` (JSON), `/actuator/health`.
   - Alternative last-mile: Cloudflare Tunnel (`cloudflared tunnel --url http://localhost:8080`); or router port-forward + DNS repoint + Caddy auto-HTTPS.

## Host-side operations (not in the repo, executed on the Debian box)

- Install Docker.
- Open firewall port 8080 on `enp1s0` (nftables) and persist.
- Retarget the Keenetic cloud publication for `www` to `192.168.1.81:8080` (HTTP).
- Create Kafka topics on the compose broker (per CLAUDE.md).

## Runtime data flow

`docker compose up -d --build` → Postgres/Redis/Kafka/app/Caddy on `petmarketplace-network` → Caddy on host `localhost:8080` serves `static/` at `/` and proxies `/api/v1/*` → `app:8080`. Public: `https://www.novgorodtsev.netcraze.link/` → Keenetic cloud (TLS) → Keenetic router → `192.168.1.81:8080` (HTTP, now allowed by firewall) → Caddy → app/Postgres/Redis/Kafka.

## Out of scope (YAGNI)

- MinIO (files kept in `./uploads` volume).
- Real SMTP (mail disabled).
- CI/CD, monitoring, backups.
- A full SPA frontend.
- Fixing apache / enabling `mod_ssl` (not needed — cloud terminates TLS).
- Restoring AmneziaWG.

## Testing / verification

- Local on the box: `curl http://localhost:8080/` (front HTML), `curl http://localhost:8080/api/v1/swagger-ui.html` (200), `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
- Public: `https://www.novgorodtsev.netcraze.link/` (front), `https://www.novgorodtsev.netcraze.link/api/v1/categories` (JSON).
- Existing integration tests (`gradle test`, Testcontainers) are untouched.

## Open items / assumptions

- The Keenetic cloud publication can be pointed at an arbitrary internal `IP:port` over HTTP. If it cannot (only fixed ports/services), fall back to Cloudflare Tunnel.
- The `static/` front exercises only public `GET` endpoints; no auth flows in the demo page.
- Firewall persistence depends on how the box manages nftables (`/etc/nftables.conf` via `nftables` service); the README documents the generic approach.