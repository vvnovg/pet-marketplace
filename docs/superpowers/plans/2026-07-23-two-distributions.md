# Two Distributions (Backend + Frontend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce two independently-deployable distributions — the Spring Boot backend (this repo) and the real Next.js frontend (`/Users/vvnovg/pet-marketplace-front`) — that run together on one Debian box and publish to `https://www.novgorodtsev.netcraze.link`, with the frontend as the public entry and the backend private.

**Architecture:** Two separate Docker Compose stacks on the same host. Backend exposes `app` on host `:8080` (firewall-closed externally); the frontend container reaches it via `host.docker.internal` (host-gateway) and serves the public on `:3000`. Keenetic cloud terminates TLS and forwards plain HTTP to `:3000`. The browser never touches the backend — the frontend's server-side proxy/auth routes call `${NEXT_PUBLIC_API_BASE}` (build-time inlined).

**Tech Stack:** Spring Boot 4 / Java 26 / Gradle 9.6.1, Next.js 15.5 / React 19 / pnpm 11.15.1 / Node 22, Docker Engine + Compose plugin, Debian + nftables, Keenetic cloud (KeenDNS).

## Global Constraints

- Build on the server, native amd64 (`git clone` + `docker compose up -d --build`). No Mac→server image shipping, no registry.
- Both stacks run on the **same physical host** (the Debian box). `host-gateway` would not work across hosts.
- Frontend reaches backend at `NEXT_PUBLIC_API_BASE=http://host.docker.internal:8080/api/v1`, inlined at **build time** (every `process.env.NEXT_PUBLIC_API_BASE` read in proxy-handler, `/api/auth/*` routes, and middleware). Changing it requires rebuilding the frontend image.
- Backend `:8080` is firewall-closed on `enp1s0` (external); frontend `:3000` is open. TLS is terminated by the Keenetic cloud — the frontend serves plain HTTP on `:3000`.
- Next.js must use `output: 'standalone'` for a minimal runtime image.
- pnpm `lockfileVersion: 9.0`; pin `"packageManager": "pnpm@11.15.1"` in the frontend `package.json`; runtime/base image is `node:22-alpine`.
- Backend prod deploy uses `docker compose -f docker-compose.yml` (the dev `docker-compose.override.yml` must NOT be loaded — it publishes Postgres/Redis/MinIO/Mailpit ports).
- The two Kafka topics are NOT auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`) — provision them after first backend start.

---

## Part A — Backend distribution (this repo)

### Task A1: Drop the demo front, expose the backend app on `:8080`

**Files:**
- Modify: `docker-compose.yml` (remove the `caddy` service; add `ports: ["8080:8080"]` to `app`)
- Delete: `Caddyfile`, `static/` directory

**Interfaces:**
- Produces: a backend compose that publishes `app` on host `:8080` (HTTP, `/api/v1/*` + `/actuator/health` + Swagger at `/api/v1/swagger-ui.html`). This is the address the frontend container will reach via `host.docker.internal:8080`.

- [ ] **Step 1: Remove the `caddy` service from `docker-compose.yml`**

Delete this entire block (currently around lines 143–157, between the `app` service's closing `networks:` and the top-level `volumes:`):

```yaml
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

- [ ] **Step 2: Add a host port to the `app` service**

In `docker-compose.yml`, change the `app` service header from:

```yaml
  app:
    build: .
    image: pet-marketplace:local
    container_name: petmarketplace-app
    environment:
```

to:

```yaml
  app:
    build: .
    image: pet-marketplace:local
    container_name: petmarketplace-app
    ports:
      - "8080:8080"
    environment:
```

- [ ] **Step 3: Delete the obsolete demo-front artifacts**

Run:
```bash
git rm Caddyfile
git rm -r static
```

- [ ] **Step 4: Validate the compose file**

Run: `docker compose -f docker-compose.yml config >/dev/null && echo OK`
Expected: `OK` (no errors; `caddy` gone, `app` has `ports: ["8080:8080"]`).

- [ ] **Step 5: Build the app image and smoke-test the backend port**

Run:
```bash
docker compose -f docker-compose.yml up -d --build
# wait for health
for i in $(seq 1 30); do curl -fsS http://localhost:8080/api/v1/actuator/health && break; sleep 2; done
```
Expected: `{"status":"UP",...}`.

- [ ] **Step 6: Verify the demo front is gone and the API is directly on :8080**

Run:
```bash
curl -fsS http://localhost:8080/ -o /dev/null -w "%{http_code}\n" || echo "non-200 (expected: no static front)"
curl -fsS http://localhost:8080/api/v1/swagger-ui.html -o /dev/null -w "%{http_code}\n"
```
Expected: first command non-200/000 (no static front); second `200` (Swagger still served by the app under its context path).

- [ ] **Step 7: Tear down**

Run: `docker compose -f docker-compose.yml down`

- [ ] **Step 8: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(deploy): expose backend app on :8080, drop caddy demo front

The real Next.js frontend (separate repo/distribution) is now the public
entry on :3000 via Keenetic cloud. The backend's caddy + static demo
front are obsolete; the app is published directly on host :8080
(firewall-closed externally, reached by the frontend via host-gateway)."
```

---

### Task A2: Rewrite the backend README "Deploy" section for the two-distribution model

**Files:**
- Modify: `README.md` (replace the `## Deploy (public demo)` section, lines 261–337)

**Interfaces:**
- Produces: committed, accurate deploy + Debian-prep instructions for the backend distribution that reference the separate frontend distribution.

- [ ] **Step 1: Replace the whole `## Deploy (public demo)` section**

Replace everything from the line `## Deploy (public demo)` through the end of the section (the `### 5. Alternative last-mile options` block, ending before the next top-level `##` or EOF) with:

````markdown
## Deploy (backend distribution)

This is the **backend** distribution. It runs the API stack in Docker Compose and exposes the app on host `:8080` — **private** (firewall-closed externally). The public site is the separate **frontend distribution** (the `pet-marketplace-front` repo, Next.js) on `:3000`; the frontend reaches this backend over the Docker host gateway. Deploy them in order: backend first, then frontend.

### 1. Prepare the Debian host (once)

```bash
# base
apt update && apt -y upgrade
apt install -y ca-certificates curl gnupg git ufw

# Docker Engine + Compose plugin (official repo)
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# non-root access to Docker
usermod -aG docker <your-user>   # then re-login

# swap for next build (skip if free RAM already >= ~2 GB)
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

### 2. Configure and start the backend stack

```bash
git clone <backend-repo-url> pet-marketplace && cd pet-marketplace
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD and JWT_SECRET (>= 256 bits, e.g. `openssl rand -base64 48`)
docker compose -f docker-compose.yml up -d --build
```

> Uses `-f docker-compose.yml` so the dev-only `docker-compose.override.yml` (which publishes Postgres/Redis/MinIO/Mailpit ports to the host) is NOT loaded on the deployment.

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
```

### 3. Firewall — keep the backend private, open the frontend port

The host's nftables `input` chain has `policy drop`. The backend must stay reachable from the frontend container (LAN/internal) but NOT from the internet. Open the **frontend** port `3000` and make sure `8080` is NOT accepted on `enp1s0`:

```bash
nft add rule inet filter input iifname "enp1s0" tcp dport 3000 accept
nft delete rule inet filter input iifname "enp1s0" tcp dport 8080 accept 2>/dev/null || true
nft -s list ruleset > /etc/nftables.conf
systemctl enable --now nftables
```

(If the host loads rules from a different file, save to that file instead; verify with `nft list chain inet filter input`.)

### 4. Next: deploy the frontend distribution

The frontend distribution (`pet-marketplace-front` repo) exposes Next.js on `:3000` and is the public entry. Follow its README "Deploy (frontend distribution)" section, then retarget the Keenetic cloud publication to `192.168.1.81:3000` (HTTP) — see the frontend repo.

Swagger UI stays private: `http://localhost:8080/api/v1/swagger-ui.html` (on the host only).
````

- [ ] **Step 2: Verify the README renders and links are consistent**

Run: `grep -n "pet-marketplace-front\|:3000\|:8080\|host-gateway" README.md`
Expected: references to the frontend repo and `:3000` (public) and `:8080` (private backend) appear; no leftover `Caddyfile`/static-front/`:80` references.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(deploy): two-distribution model — private backend :8080, public frontend :3000"
```

---

## Part B — Frontend distribution (`/Users/vvnovg/pet-marketplace-front`)

> All paths in Part B are relative to `/Users/vvnovg/pet-marketplace-front` unless noted. This is a separate git repository — commit there.

### Task B1: Enable Next.js standalone output and pin pnpm

**Files:**
- Modify: `next.config.ts`
- Modify: `package.json`

**Interfaces:**
- Produces: `pnpm build` emits `.next/standalone/server.js` + `.next/static/` + `public/` (consumed by the Dockerfile in Task B2). `packageManager` pins pnpm so `corepack enable` in the image uses `pnpm@11.15.1`.

- [ ] **Step 1: Add `output: 'standalone'` to `next.config.ts`**

Change the `nextConfig` object from:

```ts
const nextConfig: NextConfig = {
  reactStrictMode: true,
  images: {
    remotePatterns: [
      { protocol: "http", hostname: "localhost", port: "8080" },
      { protocol: "http", hostname: "localhost", port: "9000" },
    ],
  },
};
```

to:

```ts
const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  images: {
    remotePatterns: [
      { protocol: "http", hostname: "localhost", port: "8080" },
      { protocol: "http", hostname: "localhost", port: "9000" },
    ],
  },
};
```

- [ ] **Step 2: Pin pnpm via `packageManager` in `package.json`**

Add a `"packageManager"` field at the top level of `package.json` (next to `"version"`):

```json
  "version": "0.1.0",
  "packageManager": "pnpm@11.15.1",
```

- [ ] **Step 3: Verify the build emits a standalone server**

Run:
```bash
pnpm install --frozen-lockfile
pnpm build
test -f .next/standalone/server.js && echo "standalone OK"
```
Expected: build succeeds; `standalone OK`.

- [ ] **Step 4: Verify typecheck still passes (gate)**

Run: `pnpm tsc --noEmit`
Expected: no errors.

- [ ] **Step 5: Clean the build artifact and commit**

Run:
```bash
rm -rf .next
git add next.config.ts package.json
git commit -m "build: enable Next.js standalone output, pin pnpm@11.15.1"
```

---

### Task B2: Create the frontend `.dockerignore` and `Dockerfile`

**Files:**
- Create: `.dockerignore`
- Create: `Dockerfile`

**Interfaces:**
- Consumes: `.next/standalone` from Task B1's build; `NEXT_PUBLIC_API_BASE` build arg.
- Produces: a buildable image `pmp-front` that runs `node server.js` on `:3000`.

- [ ] **Step 1: Create `.dockerignore`**

Create `/Users/vvnovg/pet-marketplace-front/.dockerignore`:

```
node_modules
.next
.git
test-results
e2e
playwright-report
.env*
tsconfig.tsbuildinfo
*.log
.DS_Store
```

- [ ] **Step 2: Create the multi-stage `Dockerfile`**

Create `/Users/vvnovg/pet-marketplace-front/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1

# ---- deps ----
FROM node:22-alpine AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_API_BASE
ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE
ENV NODE_ENV=production
RUN pnpm build

# ---- runtime ----
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
```

- [ ] **Step 3: Build the image**

Run:
```bash
docker build --build-arg NEXT_PUBLIC_API_BASE=http://host.docker.internal:8080/api/v1 -t pmp-front .
```
Expected: image builds; final stage uses `node:22-alpine`.

- [ ] **Step 4: Run the container and confirm the Next server starts**

Run:
```bash
docker run --rm -d --name pmp-front-smoke -p 3000:3000 --add-host host.docker.internal:host-gateway pmp-front
sleep 3
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```
Expected: an HTTP status (likely `200`; `500` is also acceptable here if no backend is running — it proves the server started). The point is the server responds.

- [ ] **Step 5: Stop the smoke container**

Run:
```bash
docker stop pmp-front-smoke
```

- [ ] **Step 6: Commit**

```bash
git add .dockerignore Dockerfile
git commit -m "build: add standalone Dockerfile and .dockerignore for the frontend distribution"
```

---

### Task B3: Create the frontend `docker-compose.yml` and `.env.example`

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**
- Consumes: the image built from Task B2; the backend on host `:8080` (Task A1).
- Produces: `docker compose up -d --build` brings up the frontend on host `:3000`, reaching the backend via host-gateway.

- [ ] **Step 1: Create `docker-compose.yml`**

Create `/Users/vvnovg/pet-marketplace-front/docker-compose.yml`:

```yaml
services:
  front:
    build:
      context: .
      args:
        NEXT_PUBLIC_API_BASE: ${NEXT_PUBLIC_API_BASE:-http://host.docker.internal:8080/api/v1}
    image: pet-marketplace-front:local
    container_name: petmarketplace-front
    ports:
      - "3000:3000"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    restart: unless-stopped
```

- [ ] **Step 2: Create `.env.example`**

Create `/Users/vvnovg/pet-marketplace-front/.env.example`:

```
# Build-time value inlined into the Next.js image (every server-side read of
# NEXT_PUBLIC_API_BASE in the proxy, /api/auth/* routes, and middleware).
# The frontend container reaches the PRIVATE backend (same host) via the
# Docker host gateway. Changing this requires rebuilding: docker compose up -d --build
NEXT_PUBLIC_API_BASE=http://host.docker.internal:8080/api/v1
```

- [ ] **Step 3: Ensure `.env` is gitignored**

Check `.gitignore` in the frontend repo. If it does not ignore `.env`, add the line `.env` to it. Do NOT ignore `.env.example`.

Run:
```bash
grep -qxF '.env' .gitignore || printf '\n# local deploy env\n.env\n' >> .gitignore
git diff -- .gitignore
```
Expected: either no change (already ignored) or an appended `.env` line.

- [ ] **Step 4: Validate the compose file**

Run: `docker compose config >/dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 5: End-to-end smoke test with the backend running**

Start the backend (in this backend repo) on host `:8080`:
```bash
cd /Users/vvnovg/pet-marketplace
docker compose -f docker-compose.yml up -d --build
for i in $(seq 1 30); do curl -fsS http://localhost:8080/api/v1/actuator/health && break; sleep 2; done
```

Bring up the frontend:
```bash
cd /Users/vvnovg/pet-marketplace-front
cp .env.example .env
docker compose up -d --build
sleep 5
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```
Expected: `200` (home page renders; server-side proxy can reach the backend on `host.docker.internal:8080`).

- [ ] **Step 6: Confirm the frontend reaches the backend (categories via proxy)**

Run:
```bash
curl -fsS http://localhost:3000/api/proxy/categories -o /dev/null -w "%{http_code}\n" || \
  curl -fsS http://localhost:3000/api/v1/categories -o /dev/null -w "%{http_code}\n" || echo "see note"
```
Expected: `200` from the proxy path (the exact public path depends on the proxy handler; if neither returns 200, fall back to verifying the home page from Step 5 — the proxy is exercised on first load). Note: `/api/v1/*` does NOT exist on the frontend container; only `/api/proxy/*` and `/api/auth/*` do.

- [ ] **Step 7: Tear down both stacks**

Run:
```bash
cd /Users/vvnovg/pet-marketplace-front && docker compose down
cd /Users/vvnovg/pet-marketplace && docker compose -f docker-compose.yml down
```

- [ ] **Step 8: Commit**

```bash
cd /Users/vvnovg/pet-marketplace-front
git add docker-compose.yml .env.example .gitignore
git commit -m "feat(deploy): add docker-compose + .env.example for the frontend distribution"
```

---

### Task B4: Add the frontend README "Deploy (frontend distribution)" section

**Files:**
- Modify: `README.md` (frontend repo) — add a Deploy section (or extend the existing one)

**Interfaces:**
- Produces: committed, accurate deploy instructions for the frontend distribution that reference the backend distribution and the Keenetic retarget.

- [ ] **Step 1: Add the Deploy section to the frontend `README.md`**

Append (or merge into) `/Users/vvnovg/pet-marketplace-front/README.md`:

````markdown
## Deploy (frontend distribution)

This is the **frontend** distribution (Next.js). It is the public entry point on `:3000`. It requires the **backend distribution** (`pet-marketplace` repo) already running on the same host on `:8080` (see that repo's README). Deploy order: backend first, then this.

### 1. Prerequisites

The same Debian host prepared for the backend (Docker Engine + Compose plugin, git). See the backend README "Deploy (backend distribution) → 1. Prepare the Debian host".

### 2. Configure and start the frontend

```bash
git clone <frontend-repo-url> pet-marketplace-front && cd pet-marketplace-front
cp .env.example .env
# NEXT_PUBLIC_API_BASE defaults to http://host.docker.internal:8080/api/v1 (reaches the backend on the same host)
docker compose up -d --build
```

Verify locally on the host:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/   # 200
```

### 3. Firewall

The backend distribution's README already opens `:3000` on `enp1s0` and keeps `:8080` private. No extra firewall step here.

### 4. Publish via the Keenetic cloud

`netcraze.link` is a KeenDNS domain: the Keenetic cloud terminates HTTPS (Let's Encrypt cert `novgorodtsev.netcraze.link`) and tunnels to the Keenetic router, which forwards to an internal `IP:port`.

In the Keenetic web GUI (KeenDNS / "Доступ из интернета"), retarget the `www.novgorodtsev.netcraze.link` cloud publication to:

- internal host: `192.168.1.81`
- port: `3000`
- protocol: HTTP (TLS is handled by the cloud)

Then verify publicly:

```bash
curl -fsS https://www.novgorodtsev.netcraze.link/ -o /dev/null -w "%{http_code}\n"   # 200
```

The browser talks only to the frontend (auth cookies `pmp_access`/`pmp_refresh` are set on this domain); the backend is never reached directly from the browser, so no CORS is needed.

### 5. Rebuilding after a backend address/port change

`NEXT_PUBLIC_API_BASE` is inlined at build time. If the backend moves, update `.env` and rebuild:

```bash
docker compose up -d --build
```
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(deploy): add frontend distribution deploy section"
```

---

## Part C — Debian server preparation & deployment runbook

> These tasks are **executed on the Debian server** (host-side). They are not committed code — they follow the README sections produced in Tasks A2 and B4. They are included here so the plan covers the user's request to document Debian preparation and give a concrete end-to-end runbook.

### Task C1: Prepare a fresh Debian host for deployment

**Interfaces:**
- Produces: a Debian box with git, Docker Engine + Compose plugin, a non-root docker user, swap (if needed), and nftables configured (`:3000` open, `:8080` closed externally).

- [ ] **Step 1: Update the system and install base packages (as root)**

Run:
```bash
apt update && apt -y upgrade
apt install -y ca-certificates curl gnupg git ufw
```
Expected: completes without errors; `git --version` prints a version.

- [ ] **Step 2: Install Docker Engine + Compose plugin from the official repo**

Run:
```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```
Expected: `docker --version` and `docker compose version` both print versions.

- [ ] **Step 3: Grant a non-root user access to Docker**

Run (replace `<your-user>`):
```bash
usermod -aG docker <your-user>
```
Re-login as that user, then verify:
```bash
docker run --rm hello-world
```
Expected: `hello-world` runs without `sudo`.

- [ ] **Step 4: Add swap if free RAM < ~2 GB**

Run:
```bash
free -h
```
If swap is missing/small, run:
```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```
Expected: `free -h` shows ~2G swap.

- [ ] **Step 5: Configure nftables — open `:3000`, keep `:8080` private**

Run (as root):
```bash
nft add rule inet filter input iifname "enp1s0" tcp dport 3000 accept
nft delete rule inet filter input iifname "enp1s0" tcp dport 8080 accept 2>/dev/null || true
nft -s list ruleset > /etc/nftables.conf
systemctl enable --now nftables
```
Expected: `nft list chain inet filter input` shows `tcp dport 3000 accept` on `enp1s0` and no `8080 accept` on `enp1s0`.

- [ ] **Step 6: Disk space check**

Run: `df -h /`
Expected: ≥ 10 GB free.

---

### Task C2: Deploy both distributions and publish

**Interfaces:**
- Consumes: Tasks A1–A2 (backend repo) and B1–B4 (frontend repo) merged/pushed and cloneable on the server; Task C1 (host prepared).

- [ ] **Step 1: Clone both repos side by side**

Run (as the non-root docker user):
```bash
git clone <backend-repo-url>  pet-marketplace
git clone <frontend-repo-url> pet-marketplace-front
```

- [ ] **Step 2: Start the backend distribution**

Run:
```bash
cd pet-marketplace
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD and JWT_SECRET (openssl rand -base64 48)
docker compose -f docker-compose.yml up -d --build
for i in $(seq 1 40); do curl -fsS http://localhost:8080/api/v1/actuator/health && break; sleep 2; done
```
Expected: `{"status":"UP",...}`.

- [ ] **Step 3: Provision the Kafka topics**

Run:
```bash
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.requests --partitions 1 --replication-factor 1
docker exec petmarketplace-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic pet-marketplace.animal-info.replies --partitions 1 --replication-factor 1
```
Expected: both `Created topic ...`.

- [ ] **Step 4: Start the frontend distribution**

Run:
```bash
cd ../pet-marketplace-front
cp .env.example .env
docker compose up -d --build
sleep 5
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```
Expected: `200`.

- [ ] **Step 5: Retarget the Keenetic cloud publication**

In the Keenetic web GUI (KeenDNS / "Доступ из интернета"), set the `www.novgorodtsev.netcraze.link` publication to internal host `192.168.1.81`, port `3000`, protocol HTTP.

- [ ] **Step 6: Verify publicly**

Run (from anywhere):
```bash
curl -fsS https://www.novgorodtsev.netcraze.link/ -o /dev/null -w "%{http_code}\n"          # 200
curl -fsS https://www.novgorodtsev.netcraze.link/ru -o /dev/null -w "%{http_code}\n"        # 200
```
Expected: `200` for both.

- [ ] **Step 7: Confirm the backend is NOT publicly reachable**

Run (from anywhere):
```bash
curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 https://www.novgorodtsev.netcraze.link/api/v1/actuator/health || echo "blocked (expected)"
```
Expected: the frontend has no `/api/v1/*` route → `404`, OR the request times out / is refused — either way the backend's `actuator/health` is NOT exposed publicly. (The backend is only reachable internally from the frontend container.)

---

## Self-Review notes

- **Spec coverage:** Debian preparation → Task C1 (and mirrored in README A2/B4). Two separate distributions → Part A (backend) + Part B (frontend). Build on server → Global Constraints + C2. host-gateway → A1 (`app` ports) + B3 (`extra_hosts`). `:3000` open / `:8080` closed → A2 + C1. `output: 'standalone'` → B1. `packageManager` pin → B1. Keenetic retarget to `:3000` → B4 + C2. Kafka topics → A2 + C2. Swagger private → A2.
- **Placeholders:** none — every code step shows the exact file content; every command shows expected output.
- **Type/name consistency:** `NEXT_PUBLIC_API_BASE` value `http://host.docker.internal:8080/api/v1` is identical in B2 (build arg default), B3 (compose + .env.example), B4 (README), and Global Constraints. Backend port `8080`, frontend port `3000`, host `192.168.1.81`, domain `www.novgorodtsev.netcraze.link` are consistent throughout.