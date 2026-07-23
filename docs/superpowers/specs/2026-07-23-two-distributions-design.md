# Two Separate Distributions (Backend + Real Next.js Frontend) — Design Spec

Date: 2026-07-23
Status: Approved (pending implementation plan)
Supersedes the "static demo front" portion of `2026-07-22-deploy-to-internet-design.md`.

## Goal

Produce **two independent, separately-deployed distributions** — the Spring Boot backend and the real Next.js frontend (`pet-marketplace-front`, a separate repo) — that run together on the same Debian home box and are published to the existing `https://www.novgorodtsev.netcraze.link` domain via the existing Keenetic cloud + HTTPS certificate. The frontend becomes the public entry point; the backend stays private.

## Key decisions (confirmed with user)

- **Two separate distributions**, deployed independently (separate compose stacks, independent lifecycle), on the **same physical host** (the Debian box).
- **Build on the server** (native amd64) — `git clone` + `docker compose up -d --build`. No Mac→server image shipping, no registry. Same model the backend already uses; avoids the arm64→amd64 cross-build problem.
- **Frontend → backend via `host-gateway`**: backend exposes `app` on host `:8080` (firewall-closed externally); the frontend container uses `extra_hosts: host.docker.internal:host-gateway` and reaches the backend at `NEXT_PUBLIC_API_BASE=http://host.docker.internal:8080/api/v1`.

## Architecture

### Roles

- **Frontend — public entry.** Browser → `https://www.novgorodtsev.netcraze.link` (TLS terminated by Keenetic cloud) → Keenetic router → `192.168.1.81:3000` (HTTP) → Next.js standalone container.
- **Backend — private.** Its `app` listens on host `:8080`, but nftables keeps `:8080` closed on `enp1s0` (external) and opens `:3000`. Swagger (`/api/v1/swagger-ui.html`) is reachable only locally on the box (`curl http://localhost:8080/...`), not public — acceptable tradeoff for keeping the API private.
- The browser never hits the backend directly — only the frontend via relative `/api/proxy/*` and `/api/auth/*`. Auth cookies `pmp_access`/`pmp_refresh` are set on the frontend's own domain. **No CORS needed.**

### Why this is cleaner than the static-demo design

The frontend already has a server-side proxy (`src/lib/api/proxy-handler.ts`) and `/api/auth/*` route handlers that inject bearer tokens from httpOnly cookies and handle refresh/rotation. Putting the frontend as the sole public entry means the backend never sees the browser — no cross-origin, no token leakage to JS, cookies stay first-party.

## Backend distribution (changes in the backend repo)

- **Remove** the `caddy` service, `Caddyfile`, and `static/` directory — they served the superseded demo front. (If a fallback demo is desired, keep `static/`; default recommendation is to remove to avoid maintaining two fronts.)
- **`app` service**: add `ports: ["8080:8080"]` (currently has no host port — it was reached via caddy). Port `:8080` is now free since caddy is removed; no conflict.
- Everything else (`Dockerfile`, `application-prod.yml`, `.env.example`, `prod` profile, Kafka infra, `.dockerignore`) — **unchanged**.
- Deploy command unchanged: `docker compose -f docker-compose.yml up -d --build` + provision the two Kafka topics (per CLAUDE.md).
- README Deploy section: remove the "open :8080" step; replace with "keep :8080 LAN-only, open :3000 for the frontend"; note the public entry is now the frontend.

## Frontend distribution (new files in `pet-marketplace-front`)

### `next.config.ts`

Add `output: 'standalone'` (minimal runtime image — only the needed `node_modules` are copied).

### `Dockerfile` (multi-stage)

- **deps**: `node:20-alpine`; `corepack enable && corepack prepare pnpm@latest --activate`; `COPY package.json pnpm-lock.yaml pnpm-workspace.yaml`; `pnpm install --frozen-lockfile`.
- **build**: `COPY` source; `ARG NEXT_PUBLIC_API_BASE`; `ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE`; `RUN pnpm build`. The value is inlined at build time — every server-side read of `process.env.NEXT_PUBLIC_API_BASE` (proxy-handler, all `/api/auth/*` routes, middleware) receives it.
- **runtime**: `node:20-alpine`; copy `.next/standalone` + `.next/static` + `public`; `ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0`; `EXPOSE 3000`; `CMD ["node","server.js"]`.

### `.dockerignore`

`node_modules`, `.next`, `.git`, `test-results`, `e2e`, `.env*`, `tsconfig.tsbuildinfo`, `playwright-report`.

### `docker-compose.yml` (single `front` service)

- `build: .` with `args: NEXT_PUBLIC_API_BASE: http://host.docker.internal:8080/api/v1`
- `ports: ["3000:3000"]`
- `extra_hosts: ["host.docker.internal:host-gateway"]`
- `restart: unless-stopped`

### `.env.example`

`NEXT_PUBLIC_API_BASE=http://host.docker.internal:8080/api/v1` with a comment that it is build-time (baked into the image; changing it requires a rebuild).

### README

A Deploy section: `git clone` → `cp .env.example .env` → `docker compose up -d --build` → verify `curl -I http://localhost:3000`.

## Debian server preparation (from scratch, as root)

> The existing box has apache2 on :80, nftables (policy drop), and **no Docker**. The steps below bring a fresh Debian install to a deployable state. All build tooling (JDK 26 + Gradle, Node 20 + pnpm) lives inside the images — the host itself only needs `git` and Docker.

**Base packages + git:**
```bash
apt update && apt -y upgrade
apt install -y ca-certificates curl gnupg git ufw
```

**Docker Engine + Compose plugin** (official Docker repo — the distro package is stale):
```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

**Non-root access to Docker** (so deployment need not run as root):
```bash
usermod -aG docker <your-user>
# re-login, then verify:
docker run --rm hello-world
docker compose version
```

**Swap for `next build`** (if free RAM < ~2 GB the frontend build can OOM):
```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

**Firewall (nftables)** — persist via `/etc/nftables.conf` + `nftables` service:
```bash
# open the public frontend port; close the backend port from the outside
nft add rule inet filter input iif enp1s0 tcp dport 3000 accept
nft delete rule inet filter input iif enp1s0 tcp dport 8080 accept 2>/dev/null || true
nft list ruleset > /etc/nftables.conf
systemctl enable --now nftables
```

**Readiness checks:**
- `docker run --rm hello-world` → ok; `git --version`; `docker compose version`
- `free -h` → swap present
- `nft list ruleset` → `:3000` accept on `enp1s0`, `:8080` not accepted externally
- Free disk ≥ 10 GB (images, build caches, Postgres/Redis/uploads volumes)

## Host-side operations (on the Debian box, not in repos)

- `git clone` both repos (backend + frontend), each deployed with its own `docker compose -f docker-compose.yml up -d --build`.
- Bring up the **backend first** (wait for `actuator/health` → `UP`), then the **frontend**.
- Keenetic: retarget the `www.novgorodtsev.netcraze.link` cloud publication from `192.168.1.81:8080` → `192.168.1.81:3000` (HTTP).
- Provision the two Kafka topics on the compose broker (per CLAUDE.md).

## Runtime data flow

`docker compose up -d --build` (frontend) → Next.js on host `:3000`. Request `https://www.novgorodtsev.netcraze.link/` → Keenetic cloud (TLS) → router → `192.168.1.81:3000` → Next. Browser auth/data requests → `/api/proxy/*` or `/api/auth/*` (Next route handlers) → server-side `fetch(${NEXT_PUBLIC_API_BASE}/...)` = `http://host.docker.internal:8080/api/v1` → host `:8080` → backend `app` → Postgres/Redis/Kafka.

## Open items / assumptions

- **Same physical host** for both stacks. If the frontend were on a different host, `host-gateway` would not apply — fall back to the backend's LAN IP (`192.168.1.81:8080`) or a public URL.
- **Backend images (uploads)**: `next/image` with backend URLs is not used in the frontend (verified by grep). If the backend exposes uploaded files via a public file endpoint, the frontend must render them through its proxy (`/api/proxy/...`) — to verify during implementation.
- **`NEXT_PUBLIC_API_BASE` is build-time**: baked into the image; changing the backend address/port requires rebuilding the frontend. Acceptable for a single stable box.
- **Existing `2026-07-22-deploy-to-internet-design.md`**: its backend artifacts (Dockerfile, prod profile, .env.example, Kafka wiring) are reused; only the `caddy` + `static/` demo-front portion is superseded.

## Testing / verification

- On the box: `curl http://localhost:8080/api/v1/actuator/health` → `{"status":"UP"}` (backend); `curl -I http://localhost:3000` → `200` (frontend).
- Public: `https://www.novgorodtsev.netcraze.link/` renders the frontend; login flow works (cookies set on the frontend domain); `/ru` and `/en` locale routes resolve.
- Backend is NOT directly reachable from the internet (`:8080` firewall-closed) — only the frontend reaches it internally.
- Existing backend integration tests (`gradle test`, Testcontainers) are untouched; frontend tests (`pnpm test`, `pnpm tsc --noEmit`, `pnpm build`) are unaffected by the new Docker files.