<p align="center">
  <a href="README.md">🇺🇸 English</a> |
  <a href="README.pt-BR.md">🇧🇷 Português</a> |
  <a href="README.es.md">🇪🇸 Español</a> |
  <a href="README.fr.md">🇫🇷 Français</a> |
  <a href="README.it.md">🇮🇹 Italiano</a>
</p>

# ACHERNAR Observer

Local observability for LLM-powered applications — model, tokens, estimated cost, latency, prompt and response — via LiteLLM + OpenTelemetry.

> Starting from scratch? Follow **[MANUAL.md](docs/MANUAL.md)** (Observer + LiteLLM + OpenCode, step-by-step guide for beginners).

## Table of Contents

- [Why Use It](#why-use-it)
- [How It Works](#how-it-works)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Connecting LiteLLM](#connecting-litellm)
- [Development](#development)
- [Troubleshooting](#troubleshooting)
- [Privacy and Security](#privacy-and-security)
- [Repository Structure](#repository-structure)
- [Roadmap](#roadmap)

## Why Use It

- **See every LLM call** made by your coding agents, in a local dashboard.
- **Understand cost and performance**: tokens (incl. cache), duration and model per call.
- **Inspect formatted request/response** to debug prompts.
- **Real time**: updates via Server-Sent Events (SSE), no manual refresh.
- **Non-intrusive**: the Observer only observes. If it goes down, the OpenCode → LiteLLM → LLM flow keeps working.

## How It Works

```text
OpenCode  --->  LiteLLM (:4000)  --->  LLM (cloud)
                          |
                          +--> OTLP/HTTP (:4318)  --->  Observer  --->  Dashboard (:18180)
```

| Component | Technology |
|---|---|
| Backend | Java + Spring Boot (OTLP ingestion `/otel/v1/*`, API `/api/*`, SSE `/api/stream`) |
| Frontend | Angular + Nginx (dashboard at `http://localhost:18180`) |
| Telemetry | OpenTelemetry Collector (OTLP/HTTP on `:4318`) |
| Database | PostgreSQL 17 + JSONB (complete spans and events preserved) |

Ingestion details (GenAI normalization, project precedence, LLM vs. HTTP/auth span separation) are documented in the backend code.

## Prerequisites

- [Docker + Docker Compose](https://www.docker.com/products/docker-desktop/) (`docker compose version`)
- [Git](https://git-scm.com/) (`git --version`)
- API key for at least one LLM provider (e.g., DeepSeek, Gemini) — it stays **only** in LiteLLM, never in the Observer.

Default host ports (adjustable via `.env`):

| Port | Service |
|---|---|
| 18180 | Web dashboard |
| 18080 | Observer API |
| 4318 | OTLP input (telemetry) |
| 5433 | PostgreSQL (5432 deliberately avoided — almost always taken) |

## Quick Start

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Open **http://localhost:18180**. It will be empty — that's normal, there is no telemetry yet.

End-to-end test (with LiteLLM already configured):

1. In your client (e.g., OpenCode), pick a LiteLLM model and send `hello`.
2. The call shows up in the dashboard with model, tokens, duration and request/response tabs.

To follow ingestion:

```bash
docker compose logs -f otel-collector backend
```

Everyday commands:

| Action | Command |
|---|---|
| Stop (keeps data) | `docker compose down` |
| Start again | `docker compose up -d` |
| Wipe everything, including the database | `docker compose down -v && docker compose up -d --build` |
| Clear only traces | **Clear data** button in the dashboard |

## Configuration

All options live in `.env` (copied from `.env.example`) and have sensible defaults in the compose file — `.env` is only required if you deviate from the defaults.

| Variable | Default | Description |
|---|---|---|
| `FRONTEND_PORT` | `18180` | Dashboard port on the host |
| `BACKEND_PORT` | `18080` | API port on the host |
| `OTLP_PORT` | `4318` | OTLP/HTTP port on the host |
| `POSTGRES_PORT` | `5433` | Database port on the host |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `achernar_observer` / `achernar` / `achernar` | Local database credentials |
| `OBSERVER_DEFAULT_PROJECT` | `UNKNOWN` | Project fallback for a single-project instance. **Do not use** on a shared instance |
| `OBSERVER_CAPTURE_PAYLOADS` | `true` | Persists span request/response |
| `OBSERVER_CAPTURE_HEADERS` | `true` | Persists headers when OTEL provides them |
| `OBSERVER_REDACT_SECRETS` | `true` | Redacts secrets before persisting |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | `achernar-observer-backend:latest` / `achernar-observer-frontend:latest` | Point to `ghcr.io/<user>/...` to pull published images instead of building |

After editing `.env`, recreate: `docker compose up -d`.

### Publishing Images

```bash
docker compose build
docker tag achernar-observer-backend:latest ghcr.io/<user>/observer-backend:<versao>
docker tag achernar-observer-frontend:latest ghcr.io/<user>/observer-frontend:<versao>
docker push ghcr.io/<user>/observer-backend:<versao>
docker push ghcr.io/<user>/observer-frontend:<versao>
```

To run without building, set in `.env`:

```env
BACKEND_IMAGE=ghcr.io/<user>/observer-backend:<versao>
FRONTEND_IMAGE=ghcr.io/<user>/observer-frontend:<versao>
```

and start with `docker compose up -d` (without `--build`).

## Connecting LiteLLM

Summary — the full step-by-step (with ready-to-copy `config.yaml`, `.env` and `docker-compose.yml`) is in **[MANUAL.md](docs/MANUAL.md)**:

1. In the LiteLLM `config.yaml`, enable the OTEL callback:
   ```yaml
   litellm_settings:
     callbacks:
       - otel
   ```
2. In the LiteLLM `.env`, point to the Observer:
   ```env
   OTEL_EXPORTER=otlp_http
   OTEL_ENDPOINT=http://host.docker.internal:4318
   OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
   ```
   (If LiteLLM runs outside Docker, use `http://localhost:4318`.)
3. Restart LiteLLM and point your client to `http://localhost:4000/v1`.

## Development

Prerequisite: happy path (`docker compose up -d --build`) validated before publishing.

```bash
# Everything in compose + Java remote debug (attach the IDE at localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Or: only infra in compose + app outside (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Empty dashboard after using the agent | Missing `- otel` in callbacks, wrong `OTEL_ENDPOINT`, or missing `extra_hosts` on Linux |
| `host.docker.internal` does not resolve | LiteLLM outside Docker → use `http://localhost:4318` |
| Port already in use | Adjust the corresponding variable in `.env` and recreate |
| Changed `config.yaml` and nothing happened | LiteLLM only reads the yaml at startup — `docker compose down && docker compose up -d` in its folder |

Full guide with logs and verification: **[MANUAL.md §8](docs/MANUAL.md#8-common-issues)**.

## Privacy and Security

- With `SPAN_AND_EVENT`, **prompts and responses are stored in the local database** — they may include source code and sensitive data. Use in a local, trusted environment.
- Do not expose the ports to the network unless needed; the default is `localhost` usage.
- API keys stay **only** in the LiteLLM `.env`. Never commit that file.

## Repository Structure

```text
.
├── backend/               # Spring Boot — OTLP ingestion + API + SSE
├── frontend/              # Angular — dashboard
├── otel/                  # OpenTelemetry Collector config
├── docker-compose.yml     # Orchestration (happy path)
├── docker-compose.dev.yml # Debug/hot-reload overrides
├── .env.example           # All configurable variables
├── docs/                  # Step-by-step guides for beginners
│   ├── MANUAL.md          # English version (canonical docs)
│   ├── MANUAL.pt-BR.md    # Brazilian Portuguese version
│   ├── MANUAL.es.md       # Spanish version
│   ├── MANUAL.fr.md       # French version
│   └── MANUAL.it.md       # Italian version
├── README.md              # This file (technical overview, English — canonical docs)
├── README.pt-BR.md        # Brazilian Portuguese version
├── README.es.md           # Spanish version
├── README.fr.md           # French version
└── README.it.md           # Italian version
```

## Roadmap

- [ ] Dedicated aggregation of OTLP metrics and logs (accepted today, no aggregation)
- [ ] OpenCode/ACHERNAR plugin sending events to `/api/events` (`PROJECT.yaml`, agent, subagent, MCP and tool correlation with LLM spans)
