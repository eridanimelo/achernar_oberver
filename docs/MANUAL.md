<p align="center">
  <a href="MANUAL.md">🇺🇸 English</a> |
  <a href="MANUAL.pt-BR.md">🇧🇷 Português</a> |
  <a href="MANUAL.es.md">🇪🇸 Español</a> |
  <a href="MANUAL.fr.md">🇫🇷 Français</a> |
  <a href="MANUAL.it.md">🇮🇹 Italiano</a>
</p>

# MANUAL — ACHERNAR Observer

Step-by-step guide to install and use ACHERNAR Observer from scratch,
even if you have never used Docker, LiteLLM or OpenTelemetry.

## 1. What It Is and How It Works

ACHERNAR Observer is a local dashboard that shows **every AI call**
made by your coding agents (models used, tokens, estimated cost,
response time, prompt and response).

The flow looks like this:

```text
OpenCode  --->  LiteLLM (:4000)  --->  DeepSeek / Gemini (cloud)
                         |
                         +--> OTLP/HTTP (:4318)  --->  Observer  --->  Dashboard (:18180)
```

Key points:

- The Observer **only observes**. If it is off, your OpenCode + LiteLLM
  keep working normally.
- LiteLLM sends telemetry to the Observer via **OTLP/HTTP on port 4318**.
- The web dashboard lives at **http://localhost:18180**.
- No API key lives in the Observer. Keys live in LiteLLM.

## 2. Prerequisites

| Requirement | How to check | How to install |
|---|---|---|
| Docker + Docker Compose | `docker compose version` | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac) or `docker.io` + compose plugin (Linux) |
| Git | `git --version` | [git-scm.com](https://git-scm.com/) |
| DeepSeek and/or Gemini key | — | [platform.deepseek.com](https://platform.deepseek.com/) / [aistudio.google.com](https://aistudio.google.com/) |
| OpenCode (optional, only to use with an agent) | `opencode --version` | [opencode.ai](https://opencode.ai/docs) |

Ports used on your machine. If any of them is taken, see
[Changing ports](#9-changing-ports):

| Port | Service |
|---|---|
| 18180 | Observer dashboard |
| 18080 | Observer API |
| 4318 | OTLP input (telemetry) |
| 5433 | Observer PostgreSQL (the default 5432 was deliberately avoided, it is almost always already taken) |
| 4000 | LiteLLM |
| 3000 | Self-hosted Langfuse (only if you start the one in section 4.6) |

## 3. Step 1 — Start the Observer

**Option A — published images (no git clone):**

```bash
mkdir achernar-observer && cd achernar-observer
curl -o docker-compose.yml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/docker-compose.hub.yml
mkdir -p otel && curl -o otel/otel-collector-config.yaml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/otel/otel-collector-config.yaml
docker compose up -d
```

**Option B — from source:**

```bash
git clone <repository-url> achernar-observer
cd achernar-observer
cp .env.example .env
docker compose up -d --build
```

The first time takes a few minutes (it downloads images and builds).
Follow along with:

```bash
docker compose ps
docker compose logs -f backend otel-collector
```

It is ready when `docker compose ps` shows all 4 services running:

- `postgres`
- `backend`
- `otel-collector`
- `frontend`

Open the dashboard: **http://localhost:18180**

It will be empty — that is normal, there is no telemetry yet. Leave it running.

> The Observer database already listens on port 5433 on purpose, because the
> default 5432 is almost always taken by another postgres. If you want the
> default, edit `.env` and set `POSTGRES_PORT=5432` before `up`.

## 4. Step 2 — Start LiteLLM Pointing to the Observer

LiteLLM can live in **any folder**, outside the Observer repository.
Create a folder for it:

```bash
mkdir litellm && cd litellm
```

### 4.1. The LiteLLM `.env` File

Create a file called `.env` with the contents below,
replacing with your **real keys**:

```env
# --- Provider keys (paste yours) ---
DEEPSEEK_API_KEY=paste-your-deepseek-key-here
GEMINI_API_KEY=paste-your-gemini-key-here

# --- Langfuse (optional, second telemetry destination) ---
LANGFUSE_SECRET_KEY="sk-lf-your-key-here"
LANGFUSE_PUBLIC_KEY="pk-lf-your-key-here"
LANGFUSE_OTEL_HOST=http://host.docker.internal:3000

# --- Telemetry for ACHERNAR Observer ---
OTEL_EXPORTER=otlp_http
OTEL_ENDPOINT=http://host.docker.internal:4318
OTEL_SERVICE_NAME=litellm
OTEL_ENVIRONMENT_NAME=local

LITELLM_OTEL_V2=true
USE_OTEL_LITELLM_REQUEST_SPAN=true
LITELLM_OTEL_INTEGRATION_ENABLE_METRICS=true
LITELLM_OTEL_INTEGRATION_ENABLE_EVENTS=true

# We want to observe prompt/context/response.
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental
```

What each block does:

- **Providers**: keys used to call DeepSeek/Gemini.
- **Langfuse**: if you use Langfuse, it keeps receiving everything normally.
  If you do not, you can remove the `LANGFUSE_*` lines and the `- langfuse`
  entry from the `config.yaml` below.
- **Observer**: tells LiteLLM to send OTLP spans to
  `http://host.docker.internal:4318`, which is the Observer from step 1.
  `SPAN_AND_EVENT` lets you see prompt and response in the dashboard.

### 4.2. The LiteLLM `config.yaml` File

Create a file called `config.yaml` in the same folder:

```yaml
model_list:
  - model_name: deepseek-v4-pro
    litellm_params:
      model: deepseek/deepseek-v4-pro
      api_key: os.environ/DEEPSEEK_API_KEY

  - model_name: deepseek-v4-flash
    litellm_params:
      model: deepseek/deepseek-v4-flash
      api_key: os.environ/DEEPSEEK_API_KEY

  - model_name: deepseek-v4-flash-vision-exp
    litellm_params:
      model: deepseek/deepseek-v4-flash-vision-exp
      api_key: os.environ/DEEPSEEK_API_KEY

  - model_name: gemini-2.5-pro
    litellm_params:
      model: gemini/gemini-2.5-pro
      api_key: os.environ/GEMINI_API_KEY

  - model_name: gemini-2.5-flash
    litellm_params:
      model: gemini/gemini-2.5-flash
      api_key: os.environ/GEMINI_API_KEY

  - model_name: gemini-3.1-pro-preview
    litellm_params:
      model: gemini/gemini-3.1-pro-preview
      api_key: os.environ/GEMINI_API_KEY

  - model_name: gemini-3.1-pro-preview-customtools
    litellm_params:
      model: gemini/gemini-3.1-pro-preview-customtools
      api_key: os.environ/GEMINI_API_KEY

  - model_name: gemini-3.8-flash
    litellm_params:
      model: gemini/gemini-3.8-flash
      api_key: os.environ/GEMINI_API_KEY

litellm_settings:
  callbacks:
    - otel
    - langfuse
```

Details:

- `model_name` is the nickname you use in OpenCode. `model:` is the real
  model at the provider. `api_key: os.environ/...` reads the key from `.env` —
  the key is **never** written in the yaml.
- `callbacks: [otel, langfuse]` is what enables telemetry delivery.
  Without `- otel`, nothing reaches the Observer. If you do not use Langfuse,
  keep only `- otel`.
- To add or remove models, just edit the list and restart LiteLLM.

### 4.3. The LiteLLM `docker-compose.yml` File

In the same folder, create `docker-compose.yml`:

```yaml
services:
  litellm:
    image: ghcr.io/berriai/litellm:main-latest
    container_name: litellm-deepseek
    ports:
      - "4000:4000"
    env_file:
      - .env
    volumes:
      - ./config.yaml:/app/config.yaml
    command:
      - "--config"
      - "/app/config.yaml"
      - "--port"
      - "4000"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    restart: unless-stopped
```

> `extra_hosts` is what lets the container reach the Observer on port
> 4318 on Linux. On Mac/Windows it works even without this line, but keeping it
> does no harm on any system.

### 4.5. Adding New Models and Keys Later

Everything follows the same pattern — no secrets, no need to touch the Observer:

1. In the LiteLLM `.env`, add the provider key:
   ```env
   ANTHROPIC_API_KEY=paste-your-key-here
   ```
2. In `config.yaml`, add a block to `model_list` by copying an existing one:
   ```yaml
   model_list:
     # ... the existing ones ...
     - model_name: claude-sonnet-4
       litellm_params:
         model: anthropic/claude-sonnet-4-20250514
         api_key: os.environ/ANTHROPIC_API_KEY
   ```
3. Restart LiteLLM (section 7) and use the `model_name` in your client
   (section 5).

`model:` follows the LiteLLM `provider/real-model` pattern
(e.g., `openai/gpt-5`, `anthropic/claude-opus-4-1`, `xai/grok-4`).
It works for any provider LiteLLM supports.

### 4.6. Self-Hosted Langfuse via git (optional)

The `.env` from step 4.1 already points to a Langfuse at
`http://host.docker.internal:3000`. If you do not have one yet, start
your own with the official repository (in any folder, outside the Observer):

```bash
git clone https://github.com/langfuse/langfuse.git
cd langfuse
docker compose up -d
```

Open **http://localhost:3000**, create your account/organization and generate keys
under *Settings → API Keys*. Then paste into the LiteLLM `.env`:

```env
LANGFUSE_SECRET_KEY="sk-lf-your-key-here"
LANGFUSE_PUBLIC_KEY="pk-lf-your-key-here"
```

and restart LiteLLM (section 7). If you do not want Langfuse, delete the
`LANGFUSE_*` lines from `.env` and `- langfuse` from `config.yaml`.

### 4.7. Starting LiteLLM

```bash
docker compose up -d
docker compose logs -f litellm
```

Check it answers:

```bash
curl http://localhost:4000/health
```

## 5. Step 3 — Connect Your AI Client to LiteLLM

Works for **any client**: OpenCode, Cline, Roo Code, Continue,
Claude Code, Copilot CLI or any tool compatible with the OpenAI API.
The rule is always the same:

| Client field | Value |
|---|---|
| Endpoint / Base URL | `http://localhost:4000/v1` |
| API Key | any text (e.g., `sk-local`) — LiteLLM accepts it with no master key configured |
| Model | a `model_name` from your `config.yaml` (e.g., `deepseek-v4-flash`) |

The model name in the client must be **identical** to the `model_name`
in `config.yaml`. That is how LiteLLM knows which provider to route to.

### 5.1. OpenCode

In the project `opencode.json` (or global, at
`~/.config/opencode/opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "litellm": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "LiteLLM Local",
      "options": {
        "baseURL": "http://localhost:4000/v1"
      },
      "models": {
        "deepseek-v4-flash": {
          "name": "DeepSeek V4 Flash",
          "limit": { "context": 1000000, "output": 393216 }
        },
        "deepseek-v4-pro": {
          "name": "DeepSeek V4 Pro",
          "limit": { "context": 1000000, "output": 393216 }
        },
        "deepseek-v4-flash-vision-exp": {
          "name": "DeepSeek V4 Flash Vision",
          "limit": { "context": 1000000, "output": 393216 }
        },
        "gemini-2.5-pro": {
          "name": "Gemini 2.5 Pro",
          "limit": { "context": 1048576, "output": 65536 }
        },
        "gemini-2.5-flash": {
          "name": "Gemini 2.5 Flash",
          "limit": { "context": 1048576, "output": 65536 }
        },
        "gemini-3.1-pro-preview": {
          "name": "Gemini 3.1 Pro Preview",
          "limit": { "context": 1048576, "output": 65536 }
        },
        "gemini-3.1-pro-preview-customtools": {
          "name": "Gemini 3.1 Pro Preview Custom Tools",
          "limit": { "context": 1048576, "output": 65536 }
        },
        "gemini-3.8-flash": {
          "name": "Gemini 3.8 Flash",
          "limit": { "context": 1048576, "output": 65536 }
        }
      }
    }
  },
  "model": "litellm/deepseek-v4-flash"
}
```

The final `"model"` sets the default model when opening OpenCode.

### 5.2. Cline / Roo Code (VS Code)

Under *Settings → Provider*, choose **OpenAI Compatible** and fill in:

- **Base URL**: `http://localhost:4000/v1`
- **API Key**: `sk-local` (any text)
- **Model ID**: `deepseek-v4-flash` (or another `model_name` from `config.yaml`)

### 5.3. Continue (VS Code / JetBrains)

In the Continue `config.yaml`, add:

```yaml
models:
  - name: DeepSeek V4 Flash (via LiteLLM)
    provider: openai
    model: deepseek-v4-flash
    apiKey: sk-local
    apiBase: http://localhost:4000/v1
```

### 5.4. Claude Code (terminal)

Claude Code lets you switch the endpoint via environment variable:

```bash
export ANTHROPIC_BASE_URL=http://localhost:4000
export ANTHROPIC_AUTH_TOKEN=sk-local
```

and use an Anthropic model that exists in your `config.yaml`
(e.g., `claude-sonnet-4` from section 4.5).

### 5.5. GitHub Copilot

VS Code Copilot and Copilot CLI use your GitHub account models
and **do not accept a custom endpoint** — you cannot point them at LiteLLM.
For a Copilot-style flow with your models via LiteLLM, use one of
the clients above (Cline, Roo Code, Continue or OpenCode).

### 5.6. Any Other OpenAI-Compatible Client

If the tool has a custom endpoint/base URL field, apply the
table at the top of this section. If it only accepts the provider's official
key, it does not go through LiteLLM — and therefore does not show up in the Observer.

### 5.7. OpenCode Observer Plugin (Agent Flow) + Naming Your Docs

OTLP (sections 4–5) shows the LLM calls. To also see the **agent flow**
(session, agent, tools, MCP) in the span detail view, install the Observer
plugin for OpenCode V2. The file lives in this repo at
`plugins/achernar-observer.js`:

```bash
# per project
mkdir -p .opencode/plugins
cp /path/to/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

# or global (all projects)
mkdir -p ~/.config/opencode/plugins
cp /path/to/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
```

In the **project `.env`** (the one OpenCode loads), set:

```env
ACHERNAR_OBSERVER_URL=http://localhost:18080
ACHERNAR_PROJECT=my-project-name
```

Notes:

- Resolution order: `process.env` → `.env` (walked up from the plugin
  file) → default `http://localhost:18080`. Adjust the port if you changed
  `BACKEND_PORT` in the Observer `.env`.
- Without `ACHERNAR_PROJECT`, the project falls back to the event directory
  basename, then to `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` by default).
  Use one fixed name per project so the dashboard filter groups correctly.
- The plugin POSTs `session.*`, `message.*`, `tool.*` and `*mcp*` events to
  `POST /api/opencode/events` with a 1500 ms timeout. If the Observer is
  down, the agent keeps working — observability never breaks execution.
- Verify: use the agent once, then check the dashboard detail (Flow /
  Conversation / Tools tabs) and `docker compose logs -f backend`.

#### Better Flow Diagram with a Frontmatter Header

Add a YAML header at the top of your `AGENT.md`, `SKILL.md` or rule files:

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

The Flow tab uses the header `name` (fallback: folder name),
`type`/`kind` (fallback: inferred type) and `description` (shown in the node
detail). Without a header, generic filenames such as `SKILL.md` fall back to
the parent folder name — still grouped, but less readable.

## 6. Step 4 — Test End to End

1. Open OpenCode (or your client from section 5) and pick a LiteLLM model.
2. Send `hello`.
3. Open **http://localhost:18180**.
4. The call should appear in the dashboard with model, tokens, duration
   and the request/response tabs.

To follow ingestion in real time:

```bash
# in the Observer folder
docker compose logs -f otel-collector backend
```

No OpenCode? Test directly against LiteLLM:

```bash
curl http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "hello"}]}'
```

## 7. Everyday Use

| Action | Command (in the Observer folder) |
|---|---|
| Check status | `docker compose ps` |
| See logs | `docker compose logs -f backend otel-collector` |
| Stop everything (keeps data) | `docker compose down` |
| Start again | `docker compose up -d` |
| Clear dashboard (via UI) | **Clear data** button in the dashboard |
| Delete EVERYTHING, including the database | `docker compose down -v && docker compose up -d --build` |
| Restart only LiteLLM (after editing `config.yaml`/`.env`) | `docker compose down && docker compose up -d` (in the LiteLLM folder) |

## 8. Common Issues

**Empty dashboard after using OpenCode**
1. Check the callback: `config.yaml` needs `- otel` under `litellm_settings.callbacks`.
2. Check the endpoint: the LiteLLM `.env` needs `OTEL_ENDPOINT=http://host.docker.internal:4318`.
3. On Linux, check `extra_hosts` in the LiteLLM compose.
4. Watch the logs: `docker compose logs -f otel-collector backend` (in the Observer folder).

**`host.docker.internal` does not resolve (LiteLLM outside Docker)**
If LiteLLM runs directly on the machine (no container), change in its `.env`:

```env
OTEL_ENDPOINT=http://localhost:4318
```

**Port already in use**
E.g., `Bind for 0.0.0.0:4000 failed` (another LiteLLM?) or a conflict on 4318.
See the [ports section](#9-changing-ports). The Observer postgres already uses
5433 precisely to avoid clashing with 5432.

**Changed `config.yaml` and nothing happened**
LiteLLM only reads the yaml at startup:

```bash
# in the LiteLLM folder
docker compose down && docker compose up -d
```

**Want to observe a single project**
In the Observer `.env`, set `OBSERVER_DEFAULT_PROJECT=my-project`
and recreate (`docker compose up -d`). Do not use this on an instance
shared across projects — leave `UNKNOWN`.

## 9. Changing Ports

All Observer ports live in its `.env` (copied from `.env.example`):

```env
BACKEND_PORT=18080
FRONTEND_PORT=18180
OTLP_PORT=4318
POSTGRES_PORT=5433
```

Change, save and recreate: `docker compose up -d`. The containers' internal
ports do not change, so nothing else needs adjustment.

## 10. Debug for Developers

In the Observer folder there is `docker-compose.dev.yml` with two modes:

```bash
# A) Everything in compose + Java remote debug (IDE on localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# B) Only infra in compose + app outside (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
OTEL_EXPORTER_ENDPOINT=http://host.docker.internal:8080/otel \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d otel-collector --no-deps
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## 11. Privacy and Security

- With `SPAN_AND_EVENT`, **prompts and responses are stored in the local database**.
  That may include source code and sensitive data. Use in a local,
  trusted environment.
- Do not expose ports on the network (`0.0.0.0`) unless you know what you are doing;
  this manual assumes `localhost` usage.
- API keys live **only** in the LiteLLM `.env`. Never commit that
  file to git.
