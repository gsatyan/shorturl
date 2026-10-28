# URL Shortener

A URL shortener service: create short links through a REST API, redirect `GET /{code}` with a p99 under 50 ms, and collect click analytics.

- **Stack:** Java 21, Spring Boot 4.1, DynamoDB (single table + GSI), Redis, Kafka, ClickHouse, nginx, Prometheus, Grafana.
- **Design:** [`docs/DESIGN.md`](docs/DESIGN.md) is the source of truth: requirements, API, architecture, data model and trade-offs.
- **Contributor and agent rules:** [`CLAUDE.md`](CLAUDE.md).

```
client ──► nginx :8080 ──► url-shortener ×2 ──► Caffeine → Redis → DynamoDB   (redirect lookup)
                                │
                                ├──► Kafka "clicks" ──► click processor ──► ClickHouse   (stats)
DynamoDB Stream ──► relay ──► Kafka "link-events" ──► every instance evicts its caches
```

## Prerequisites

| Tool | Why | Notes |
|---|---|---|
| Docker Desktop (with Compose v2) | Local infrastructure, app containers, Testcontainers | Allocate at least 6 GB of memory |
| JDK 21 | Only to build or test on the host | Optional: you can build and test inside Docker (see [Running without a local JDK](#running-without-a-local-jdk)) |
| `curl` | Calling the API | |

On this machine Docker lives in `~/.docker/bin`, which isn't on `PATH`. Either prefix commands or export it once per shell:

```bash
export PATH="$HOME/.docker/bin:$PATH"
```

## Quick start

Start everything (infrastructure, two app replicas, the stream relay and nginx on port 8080):

```bash
docker compose --profile app up -d --build
docker compose ps          # wait until load-balancer and url-shortener are healthy
```

Create a link, follow it, then look at its stats. The local API key is seeded by the `dynamodb-init` job:

```bash
KEY="Authorization: Bearer dev-local-api-key"

# Create (generated code)
curl -s -X POST localhost:8080/api/v1/urls -H "$KEY" -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://www.schwab.com/research"}'

# Create with a custom alias, an expiry and an idempotency key
curl -s -X POST localhost:8080/api/v1/urls -H "$KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: readme-example-1' \
  -d '{"longUrl":"https://www.schwab.com/learn","customAlias":"learn","expiresAt":"2027-01-01T00:00:00Z"}'

# Redirect (302 with Location)
curl -si localhost:8080/learn | head -5

# Stats (clicks reach ClickHouse a few seconds later)
curl -s "localhost:8080/api/v1/urls/learn/stats?granularity=DAY" -H "$KEY"
```

## Running modes

| Command | What starts |
|---|---|
| `docker compose up -d` | Infrastructure only: DynamoDB Local, Kafka, Redis, ClickHouse, plus one-shot init jobs that create the table, seed the dev key and create the topics |
| `docker compose --profile app up -d --build` | + 2 `url-shortener` replicas, 1 `relay`, nginx load balancer on `:8080` |
| `docker compose --profile app --profile monitoring up -d --build` | + Prometheus `:9090`, Grafana `:3000` and exporters |
| `docker compose --profile app up -d --scale url-shortener=4` | Change the replica count |

### Run the app from your IDE or the host

Start the infrastructure only, then run the app on the host. The defaults in `application.yaml` point at `localhost`:

```bash
docker compose up -d
./mvnw spring-boot:run      # app on http://localhost:8080
```

DynamoDB Local needs some AWS credentials; any values work:

```bash
export AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local
```

Cache invalidation needs the stream relay. To run it on the host, start a second instance with `APP_RELAY_ENABLED=true SERVER_PORT=8081`. Run exactly one relay.

### Local ports

All ports are bound to `127.0.0.1` only.

| Port | Service | Notes |
|---|---|---|
| 8080 | nginx → app | API and redirects |
| 8000 | DynamoDB Local | `aws dynamodb scan --table-name url_shortener --endpoint-url http://localhost:8000` (debugging only) |
| 9094 | Kafka | Host listener; containers use `kafka:9092` |
| 6379 | Redis | `redis-cli` |
| 8123 | ClickHouse HTTP | user `app`, password `local`, database `analytics` |
| 9090 | Prometheus | monitoring profile |
| 3000 | Grafana | monitoring profile, no login; dashboard "URL Shortener overview" |

## API

All management endpoints need `Authorization: Bearer <api-key>`. Errors are RFC 9457 `application/problem+json`. Full contract: [`docs/DESIGN.md` §4](docs/DESIGN.md#4-api).

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/urls` | Create a link (`longUrl`, optional `customAlias`, `expiresAt`; optional `Idempotency-Key` header) | `201` |
| `GET` | `/{code}` | Redirect | `302` |
| `GET` | `/api/v1/urls/{code}` | Link metadata | `200` |
| `GET` | `/api/v1/urls?limit=50&cursor=…` | List your links, newest first | `200` |
| `PATCH` | `/api/v1/urls/{code}` | Change `status` (`ACTIVE`/`DISABLED`) or `expiresAt` | `200` |
| `DELETE` | `/api/v1/urls/{code}` | Disable (soft delete; codes are never reused) | `204` |
| `GET` | `/api/v1/urls/{code}/stats?from=…&to=…&granularity=DAY\|HOUR&includeBots=false` | Click analytics | `200` |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Health and metrics (nginx exposes only `/actuator/health`; Prometheus scrapes each replica directly) | `200` |

Behaviour worth knowing:

- Another owner's link returns `404`, never `403`.
- Unknown codes return `404`; expired or disabled links return `410`.
- `/api/**` is rate limited per client IP (300/min) and per owner (60 writes/min, 600 reads/min). Over the limit you get `429` with `Retry-After`.
- A retried create with the same `Idempotency-Key` and body returns the original response with `Idempotent-Replayed: true`.

More examples:

```bash
curl -s "localhost:8080/api/v1/urls?limit=10" -H "$KEY"
curl -s -X PATCH localhost:8080/api/v1/urls/learn -H "$KEY" -H 'Content-Type: application/json' -d '{"status":"DISABLED"}'
curl -s -X DELETE localhost:8080/api/v1/urls/learn -H "$KEY" -o /dev/null -w '%{http_code}\n'
```

## Testing

Tests use Testcontainers, so Docker must be running.

```bash
./mvnw test                                       # whole suite (unit + integration)
./mvnw test -Dtest=LinkValidatorTests             # one class
./mvnw test -Dtest='LinkValidatorTests#rejects*'  # matching methods
```

- `*Tests` are unit tests: no Spring context.
- `*IntegrationTests` start the Spring context against real DynamoDB Local, Kafka, Redis and ClickHouse containers (`TestcontainersConfiguration`).

A change is done only when `./mvnw test` passes.

### Running without a local JDK

If there's no JDK on the host, run the suite in a Maven container that can reach the host's Docker daemon:

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD":/workspace -w /workspace \
  -v shorturl-m2:/root/.m2 \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B test
```

The `shorturl-m2` volume caches dependencies between runs.

**Behind a TLS-intercepting proxy (for example Zscaler)**, Maven downloads inside containers fail with certificate errors, both in this command and in `docker compose ... --build`. Export the proxy's root CA and trust it in the container:

```bash
security find-certificate -a -c "Zscaler Root CA" -p /Library/Keychains/System.keychain > /tmp/zscaler.pem
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD":/workspace -w /workspace \
  -v /tmp/zscaler.pem:/zscaler.pem:ro -v shorturl-m2:/root/.m2 \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 sh -c '
    cp /zscaler.pem /usr/local/share/ca-certificates/zscaler.crt && update-ca-certificates >/dev/null &&
    keytool -importcert -noprompt -cacerts -storepass changeit -alias zscaler -file /zscaler.pem >/dev/null &&
    ./mvnw -B test'
```

For image builds, add the same CA import to the build stage of a local copy of the `Dockerfile` (don't commit it), build with `docker buildx build --load`, tag the result as `schwab-url-shortener-url-shortener` and `schwab-url-shortener-relay`, and run `docker compose --profile app up -d` without `--build`.

## Configuration

Runtime settings live in [`src/main/resources/application.yaml`](src/main/resources/application.yaml) under `app.*`, each with a comment explaining the default. The main environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `DYNAMODB_ENDPOINT` | `http://localhost:8000` | DynamoDB Local; empty in AWS to use the regional endpoint |
| `DYNAMODB_TABLE` | `url_shortener` | Table name |
| `AWS_REGION` | `us-east-1` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `CLICKHOUSE_URL` / `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` | `jdbc:clickhouse://localhost:8123/analytics` / `app` / `local` | |
| `APP_BASE_URL` | `http://localhost:8080` | Prefix of returned `shortUrl`s |
| `APP_RELAY_ENABLED` | `false` | Turns on the DynamoDB Stream relay; exactly one instance per region |
| `LINKS_CURSOR_SECRET` | `dev-only-cursor-secret` | Signs list cursors. **Set a real secret outside development** |
| `ANALYTICS_VISITOR_SALT_SECRET` | `dev-only-visitor-salt-secret` | Salts visitor hashes. **Set a real secret outside development** |
| `DEV_API_KEY` | `dev-local-api-key` | Key seeded by `dynamodb-init` (local only; override in `.env`) |

Never commit real credentials or `.env` files.

## Monitoring

```bash
docker compose --profile app --profile monitoring up -d --build
```

- Grafana: http://localhost:3000 (dashboard "URL Shortener overview": redirect latency, cache hit ratio, Kafka lag, error rates)
- Prometheus: http://localhost:9090
- Per-instance health: `/actuator/health/liveness`, `/actuator/health/readiness` (readiness gates on DynamoDB only; Redis has a DynamoDB fallback)

## Project layout

```
src/main/java/com/schwab/assessment/schwab_url_shortener/
  redirect/    GET /{code}, cache tiers
  link/        create, list, update, disable
  stats/       ClickHouse stats queries
  click/       click events → Kafka
  analytics/   click processor → ClickHouse
  relay/       DynamoDB Stream → link-events
  auth/        API keys, OwnerResolver
  ratelimit/   per-IP and per-owner limits on /api/**
  config/      DynamoDB client, health
  domain/      shared types, ApiException
docker/        DynamoDB table definition, ClickHouse schema, nginx, Prometheus, Grafana
docs/DESIGN.md system design
```

`redirect`, `link` and `stats` never import each other, so they can be split into separate services later.

## Stopping and cleaning up

```bash
docker compose --profile app --profile monitoring down      # stop, keep data
docker compose --profile app --profile monitoring down -v   # stop and delete all local data (tables, topics, clicks)
```

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `docker: command not found` | Add `~/.docker/bin` to `PATH` (see Prerequisites) |
| `401` on `/api/**` | Missing or wrong `Authorization: Bearer dev-local-api-key` header; check `docker compose logs dynamodb-init` shows the key was seeded |
| `--build` fails downloading Maven dependencies with certificate errors | TLS-intercepting proxy; see [Running without a local JDK](#running-without-a-local-jdk) |
| `Unable to locate a Java Runtime` | No JDK on the host; install JDK 21 or run tests in Docker |
| Stats stay empty | Clicks are processed asynchronously; wait a few seconds, then check `docker compose logs url-shortener` and the `clicks` topic lag in Grafana |
| Updated link still redirects to the old URL | The relay invalidates caches; check `docker compose logs relay`. Local caches also expire after 60 s |
| Redirects return `503` | DynamoDB is unreachable and the link isn't cached; check `docker compose ps dynamodb` |
