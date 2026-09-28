<p align="center">
  <a href="MANUAL.md">🇺🇸 English</a> |
  <a href="MANUAL.pt-BR.md">🇧🇷 Português</a> |
  <a href="MANUAL.es.md">🇪🇸 Español</a> |
  <a href="MANUAL.fr.md">🇫🇷 Français</a> |
  <a href="MANUAL.it.md">🇮🇹 Italiano</a>
</p>

# MANUAL — ACHERNAR Observer

Guida passo passo per installare e usare ACHERNAR Observer da zero,
anche se non hai mai usato Docker, LiteLLM o OpenTelemetry.

## 1. Cos'è e come funziona

ACHERNAR Observer è un pannello locale che mostra **tutte le chiamate IA**
effettuate dai tuoi agenti di codice (modelli usati, token, costo stimato,
tempo di risposta, prompt e risposta).

Il flusso è così:

```text
OpenCode  --->  LiteLLM (:4000)  --->  DeepSeek / Gemini (cloud)
                         |
                         +--> OTLP/HTTP (:4318)  --->  Observer  --->  Pannello (:18180)
```

Punti importanti:

- L'Observer **osserva soltanto**. Se è spento, il tuo OpenCode + LiteLLM
  continuano a funzionare normalmente.
- LiteLLM invia la telemetria all'Observer via **OTLP/HTTP sulla porta 4318**.
- Il pannello web si trova su **http://localhost:18180**.
- Nessuna chiave API resta nell'Observer. Le chiavi stanno in LiteLLM.

## 2. Prerequisiti

| Requisito | Come verificare | Come installare |
|---|---|---|
| Docker + Docker Compose | `docker compose version` | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac) oppure `docker.io` + plugin compose (Linux) |
| Git | `git --version` | [git-scm.com](https://git-scm.com/) |
| Chiave DeepSeek e/o Gemini | — | [platform.deepseek.com](https://platform.deepseek.com/) / [aistudio.google.com](https://aistudio.google.com/) |
| OpenCode (opzionale, solo per usare con agente) | `opencode --version` | [opencode.ai](https://opencode.ai/docs) |

Porte usate sul tuo computer. Se qualcuna è occupata, vedi
[Cambiare le porte](#9-cambiare-le-porte):

| Porta | Servizio |
|---|---|
| 18180 | Pannello dell'Observer |
| 18080 | API dell'Observer |
| 4318 | Ingresso OTLP (telemetria) |
| 5433 | PostgreSQL dell'Observer (la 5432 predefinita è stata volutamente evitata, è quasi sempre già occupata) |
| 4000 | LiteLLM |
| 3000 | Langfuse proprio (solo se avvii quello della sezione 4.6) |

## 3. Passo 1 — Avviare l'Observer

```bash
git clone <url-del-repository> achernar-observer
cd achernar-observer
cp .env.example .env
docker compose up -d --build
```

La prima volta richiede alcuni minuti (scarica le immagini e compila).
Segui con:

```bash
docker compose ps
docker compose logs -f backend otel-collector
```

È pronto quando `docker compose ps` mostra i 4 servizi in esecuzione:

- `postgres`
- `backend`
- `otel-collector`
- `frontend`

Apri il pannello: **http://localhost:18180**

Sarà vuoto — è normale, non c'è ancora telemetria. Lascialo in esecuzione.

> Il database dell'Observer esce già sulla porta 5433 di proposito, perché la
> 5432 predefinita è quasi sempre già occupata da un altro postgres. Se vuoi la
> predefinita, modifica `.env` e imposta `POSTGRES_PORT=5432` prima di `up`.

## 4. Passo 2 — Avviare LiteLLM puntato all'Observer

LiteLLM può stare in **qualsiasi cartella**, fuori dal repository dell'Observer.
Crea una cartella per lui:

```bash
mkdir litellm && cd litellm
```

### 4.1. File `.env` di LiteLLM

Crea un file chiamato `.env` con il contenuto qui sotto,
sostituendo con le tue **chiavi reali**:

```env
# --- Chiavi dei provider (incolla le tue) ---
DEEPSEEK_API_KEY=incolla-qui-la-tua-chiave-deepseek
GEMINI_API_KEY=incolla-qui-la-tua-chiave-gemini

# --- Langfuse (opzionale, seconda destinazione della telemetria) ---
LANGFUSE_SECRET_KEY="sk-lf-tua-chiave-qui"
LANGFUSE_PUBLIC_KEY="pk-lf-tua-chiave-qui"
LANGFUSE_OTEL_HOST=http://host.docker.internal:3000

# --- Telemetria per ACHERNAR Observer ---
OTEL_EXPORTER=otlp_http
OTEL_ENDPOINT=http://host.docker.internal:4318
OTEL_SERVICE_NAME=litellm
OTEL_ENVIRONMENT_NAME=local

LITELLM_OTEL_V2=true
USE_OTEL_LITELLM_REQUEST_SPAN=true
LITELLM_OTEL_INTEGRATION_ENABLE_METRICS=true
LITELLM_OTEL_INTEGRATION_ENABLE_EVENTS=true

# Vogliamo osservare prompt/contesto/risposta.
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental
```

Cosa fa ogni blocco:

- **Provider**: chiavi usate per chiamare DeepSeek/Gemini.
- **Langfuse**: se usi Langfuse, continua a ricevere tutto normalmente.
  Se non lo usi, puoi rimuovere le righe `LANGFUSE_*` e il `- langfuse`
  dal `config.yaml` qui sotto.
- **Observer**: dice a LiteLLM di inviare span OTLP a
  `http://host.docker.internal:4318`, cioè l'Observer del passo 1.
  `SPAN_AND_EVENT` permette di vedere prompt e risposta nel pannello.

### 4.2. File `config.yaml` di LiteLLM

Crea un file chiamato `config.yaml` nella stessa cartella:

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

Dettagli:

- `model_name` è il soprannome che usi in OpenCode. `model:` è il modello
  reale presso il provider. `api_key: os.environ/...` legge la chiave da `.env` —
  la chiave **non** resta mai scritta nello yaml.
- `callbacks: [otel, langfuse]` è ciò che attiva l'invio della telemetria.
  Senza `- otel`, nulla arriva all'Observer. Se non usi Langfuse, lascia solo
  `- otel`.
- Per aggiungere o rimuovere modelli, modifica solo la lista e riavvia LiteLLM.

### 4.3. File `docker-compose.yml` di LiteLLM

Nella stessa cartella, crea `docker-compose.yml`:

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

> `extra_hosts` è ciò che permette al container di raggiungere l'Observer sulla
> porta 4318 su Linux. Su Mac/Windows funziona anche senza questa riga, ma
> mantenerla non fa male su nessun sistema.

### 4.5. Aggiungere modelli e chiavi in seguito

Tutto segue lo stesso schema — niente di misterioso e senza toccare l'Observer:

1. Nel `.env` di LiteLLM, aggiungi la chiave del provider:
   ```env
   ANTHROPIC_API_KEY=incolla-qui-la-tua-chiave
   ```
2. In `config.yaml`, aggiungi un blocco a `model_list` copiandone uno esistente:
   ```yaml
   model_list:
     # ... quelli esistenti ...
     - model_name: claude-sonnet-4
       litellm_params:
         model: anthropic/claude-sonnet-4-20250514
         api_key: os.environ/ANTHROPIC_API_KEY
   ```
3. Riavvia LiteLLM (sezione 7) e usa il `model_name` nel tuo client
   (sezione 5).

Il `model:` segue lo schema LiteLLM `provider/modello-reale`
(es.: `openai/gpt-5`, `anthropic/claude-opus-4-1`, `xai/grok-4`).
Vale per qualsiasi provider supportato da LiteLLM.

### 4.6. Langfuse proprio via git (opzionale)

Il `.env` del passo 4.1 punta già a un Langfuse su
`http://host.docker.internal:3000`. Se non ne hai ancora uno, avvia
il tuo con il repository ufficiale (in qualsiasi cartella, fuori dall'Observer):

```bash
git clone https://github.com/langfuse/langfuse.git
cd langfuse
docker compose up -d
```

Apri **http://localhost:3000**, crea account/organizzazione e genera le chiavi
in *Settings → API Keys*. Poi incollale nel `.env` di LiteLLM:

```env
LANGFUSE_SECRET_KEY="sk-lf-tua-chiave-qui"
LANGFUSE_PUBLIC_KEY="pk-lf-tua-chiave-qui"
```

e riavvia LiteLLM (sezione 7). Se non vuoi Langfuse, elimina le righe
`LANGFUSE_*` da `.env` e `- langfuse` da `config.yaml`.

### 4.7. Avviare LiteLLM

```bash
docker compose up -d
docker compose logs -f litellm
```

Verifica che risponda:

```bash
curl http://localhost:4000/health
```

## 5. Passo 3 — Collegare il tuo client IA a LiteLLM

Vale per **qualsiasi client**: OpenCode, Cline, Roo Code, Continue,
Claude Code, Copilot CLI o qualsiasi strumento compatibile con l'API
di OpenAI. La regola è sempre la stessa:

| Campo nel client | Valore |
|---|---|
| Endpoint / Base URL | `http://localhost:4000/v1` |
| API Key | testo qualsiasi (es.: `sk-local`) — LiteLLM lo accetta senza chiave master configurata |
| Modello | un `model_name` del tuo `config.yaml` (es.: `deepseek-v4-flash`) |

Il nome del modello nel client deve essere **identico** al `model_name`
di `config.yaml`. È così che LiteLLM sa verso quale provider instradare.

### 5.1. OpenCode

In `opencode.json` del progetto (o globale, in
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

Il `"model"` finale definisce il modello predefinito all'apertura di OpenCode.

### 5.2. Cline / Roo Code (VS Code)

In *Settings → Provider*, scegli **OpenAI Compatible** e compila:

- **Base URL**: `http://localhost:4000/v1`
- **API Key**: `sk-local` (testo qualsiasi)
- **Model ID**: `deepseek-v4-flash` (o un altro `model_name` di `config.yaml`)

### 5.3. Continue (VS Code / JetBrains)

In `config.yaml` di Continue, aggiungi:

```yaml
models:
  - name: DeepSeek V4 Flash (via LiteLLM)
    provider: openai
    model: deepseek-v4-flash
    apiKey: sk-local
    apiBase: http://localhost:4000/v1
```

### 5.4. Claude Code (terminale)

Claude Code permette di cambiare l'endpoint via variabile d'ambiente:

```bash
export ANTHROPIC_BASE_URL=http://localhost:4000
export ANTHROPIC_AUTH_TOKEN=sk-local
```

e usa un modello Anthropic presente nel tuo `config.yaml`
(es.: `claude-sonnet-4` della sezione 4.5).

### 5.5. GitHub Copilot

Copilot di VS Code e Copilot CLI usano i modelli del tuo account GitHub
e **non accettano endpoint personalizzati** — non puoi puntarli a LiteLLM.
Per un flusso stile Copilot con i tuoi modelli via LiteLLM, usa uno dei
client qui sopra (Cline, Roo Code, Continue o OpenCode).

### 5.6. Qualsiasi altro client OpenAI-compatible

Se lo strumento ha un campo di endpoint/base URL personalizzato, applica la
tabella all'inizio di questa sezione. Se accetta solo la chiave ufficiale del
provider, non passa da LiteLLM — e quindi non appare nell'Observer.

## 6. Passo 4 — Test end-to-end

1. Apri OpenCode (o il tuo client della sezione 5) e scegli un modello LiteLLM.
2. Invia `buongiorno`.
3. Apri **http://localhost:18180**.
4. La chiamata deve apparire nel pannello con modello, token, durata
   e schede request/response.

Per seguire l'ingestione in tempo reale:

```bash
# nella cartella dell'Observer
docker compose logs -f otel-collector backend
```

Senza OpenCode? Test diretto contro LiteLLM:

```bash
curl http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "buongiorno"}]}'
```

## 7. Uso quotidiano

| Azione | Comando (nella cartella dell'Observer) |
|---|---|
| Vedere lo stato | `docker compose ps` |
| Vedere i log | `docker compose logs -f backend otel-collector` |
| Fermare tutto (conserva i dati) | `docker compose down` |
| Riavviare | `docker compose up -d` |
| Pulire il pannello (via UI) | Pulsante **Cancella dati** nel pannello |
| Cancellare TUTTO, database incluso | `docker compose down -v && docker compose up -d --build` |
| Riavviare solo LiteLLM (dopo aver modificato `config.yaml`/`.env`) | `docker compose down && docker compose up -d` (nella cartella di LiteLLM) |

## 8. Problemi comuni

**Pannello vuoto dopo aver usato OpenCode**
1. Controlla il callback: `config.yaml` deve avere `- otel` in `litellm_settings.callbacks`.
2. Controlla l'endpoint: `.env` di LiteLLM deve avere `OTEL_ENDPOINT=http://host.docker.internal:4318`.
3. Su Linux, controlla `extra_hosts` nel compose di LiteLLM.
4. Guarda i log: `docker compose logs -f otel-collector backend` (nella cartella dell'Observer).

**`host.docker.internal` non risolve (LiteLLM fuori da Docker)**
Se LiteLLM gira direttamente sulla macchina (senza container), cambia nel suo `.env`:

```env
OTEL_ENDPOINT=http://localhost:4318
```

**Porta già in uso**
Es.: `Bind for 0.0.0.0:4000 failed` (un altro LiteLLM?) o conflitto sulla 4318.
Vedi la [sezione delle porte](#9-cambiare-le-porte). Il postgres dell'Observer esce già
sulla 5433 proprio per non scontrarsi con quello sulla 5432.

**Modificato `config.yaml` senza effetto**
LiteLLM legge lo yaml solo all'avvio:

```bash
# nella cartella di LiteLLM
docker compose down && docker compose up -d
```

**Voglio osservare un solo progetto**
Nel `.env` dell'Observer, imposta `OBSERVER_DEFAULT_PROJECT=mio-progetto`
e ricrea (`docker compose up -d`). Non usarlo su un'istanza
condivisa tra progetti — lascia `UNKNOWN`.

## 9. Cambiare le porte

Tutte le porte dell'Observer stanno nel suo `.env` (copiato da `.env.example`):

```env
BACKEND_PORT=18080
FRONTEND_PORT=18180
OTLP_PORT=4318
POSTGRES_PORT=5433
```

Modifica, salva e ricrea: `docker compose up -d`. Le porte interne dei
container non cambiano, quindi nient'altro va regolato.

## 10. Debug per sviluppatori

Nella cartella dell'Observer c'è `docker-compose.dev.yml` con due modalità:

```bash
# A) Tutto nel compose + debug remoto Java (IDE su localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# B) Solo infra nel compose + app fuori (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
OTEL_EXPORTER_ENDPOINT=http://host.docker.internal:8080/otel \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d otel-collector --no-deps
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## 11. Privacy e sicurezza

- Con `SPAN_AND_EVENT`, **prompt e risposte sono salvati nel database locale**.
  Possono includere codice sorgente e dati sensibili. Usa in un ambiente locale
  e affidabile.
- Non esporre le porte in rete (`0.0.0.0`) senza sapere cosa fai;
  questo manuale presume uso su `localhost`.
- Le chiavi API restano **solo** nel `.env` di LiteLLM. Non committare mai
  quel file in git.
