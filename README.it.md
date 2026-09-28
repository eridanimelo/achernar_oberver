<p align="center">
  <a href="README.md">🇺🇸 English</a> |
  <a href="README.pt-BR.md">🇧🇷 Português</a> |
  <a href="README.es.md">🇪🇸 Español</a> |
  <a href="README.fr.md">🇫🇷 Français</a> |
  <a href="README.it.md">🇮🇹 Italiano</a>
</p>

# ACHERNAR Observer

Osservabilità locale per applicazioni basate su LLM — modello, token, costo stimato, latenza, prompt e risposta — tramite LiteLLM + OpenTelemetry.

> Installazione da zero? Segui il **[MANUAL.md](docs/MANUAL.it.md)** (Observer + LiteLLM + OpenCode, guida passo passo per principianti).

## Indice

- [Perché usarlo](#perché-usarlo)
- [Come funziona](#come-funziona)
- [Prerequisiti](#prerequisiti)
- [Avvio rapido](#avvio-rapido)
- [Configurazione](#configurazione)
- [Collegare LiteLLM](#collegare-litellm)
- [Sviluppo](#sviluppo)
- [Risoluzione dei problemi](#risoluzione-dei-problemi)
- [Privacy e sicurezza](#privacy-e-sicurezza)
- [Struttura del repository](#struttura-del-repository)
- [Roadmap](#roadmap)

## Perché usarlo

- **Vedi ogni chiamata LLM** effettuata dai tuoi agenti di codice, in un pannello locale.
- **Comprendi costi e prestazioni**: token (incl. cache), durata e modello per chiamata.
- **Ispeziona request/response** formattate per fare debug dei prompt.
- **Tempo reale**: aggiornamenti via Server-Sent Events (SSE), senza refresh manuale.
- **Non intrusivo**: l'Observer osserva soltanto. Se va giù, il flusso OpenCode → LiteLLM → LLM continua a funzionare.

## Come funziona

```text
OpenCode  --->  LiteLLM (:4000)  --->  LLM (cloud)
                          |
                          +--> OTLP/HTTP (:4318)  --->  Observer  --->  Pannello (:18180)
```

| Componente | Tecnologia |
|---|---|
| Backend | Java + Spring Boot (ingestione OTLP `/otel/v1/*`, API `/api/*`, SSE `/api/stream`) |
| Frontend | Angular + Nginx (pannello su `http://localhost:18180`) |
| Telemetria | OpenTelemetry Collector (OTLP/HTTP su `:4318`) |
| Database | PostgreSQL 17 + JSONB (span ed eventi completi preservati) |

I dettagli di ingestione (normalizzazione GenAI, precedenza di progetto, separazione degli span LLM vs. HTTP/auth) sono documentati nel codice del backend.

## Prerequisiti

- [Docker + Docker Compose](https://www.docker.com/products/docker-desktop/) (`docker compose version`)
- [Git](https://git-scm.com/) (`git --version`)
- Chiave di almeno un provider LLM (es.: DeepSeek, Gemini) — resta **solo** in LiteLLM, mai nell'Observer.

Porte predefinite sull'host (regolabili via `.env`):

| Porta | Servizio |
|---|---|
| 18180 | Pannello web |
| 18080 | API dell'Observer |
| 4318 | Ingresso OTLP (telemetria) |
| 5433 | PostgreSQL (5432 volutamente evitata — quasi sempre occupata) |

## Avvio rapido

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Apri **http://localhost:18180**. Sarà vuoto — è normale, non c'è ancora telemetria.

Test end-to-end (con LiteLLM già configurato):

1. Nel tuo client (es.: OpenCode), scegli un modello LiteLLM e invia `buongiorno`.
2. La chiamata appare nel pannello con modello, token, durata e schede request/response.

Per seguire l'ingestione:

```bash
docker compose logs -f otel-collector backend
```

Comandi quotidiani:

| Azione | Comando |
|---|---|
| Fermare (conserva i dati) | `docker compose down` |
| Riavviare | `docker compose up -d` |
| Cancellare tutto, database incluso | `docker compose down -v && docker compose up -d --build` |
| Pulire solo le trace | Pulsante **Cancella dati** nel pannello |

## Configurazione

Tutte le opzioni si trovano in `.env` (copiato da `.env.example`) e hanno default sensati nel compose — `.env` è obbligatorio solo se devii dallo standard.

| Variabile | Default | Descrizione |
|---|---|---|
| `FRONTEND_PORT` | `18180` | Porta del pannello sull'host |
| `BACKEND_PORT` | `18080` | Porta dell'API sull'host |
| `OTLP_PORT` | `4318` | Porta OTLP/HTTP sull'host |
| `POSTGRES_PORT` | `5433` | Porta del database sull'host |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `achernar_observer` / `achernar` / `achernar` | Credenziali del database locale |
| `OBSERVER_DEFAULT_PROJECT` | `UNKNOWN` | Progetto di fallback per istanza a progetto singolo. **Non usare** su istanza condivisa |
| `OBSERVER_CAPTURE_PAYLOADS` | `true` | Persiste request/response degli span |
| `OBSERVER_CAPTURE_HEADERS` | `true` | Persiste gli header quando OTEL li fornisce |
| `OBSERVER_REDACT_SECRETS` | `true` | Oscura i segreti prima di persistere |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | `achernar-observer-backend:latest` / `achernar-observer-frontend:latest` | Punta a `ghcr.io/<user>/...` per scaricare le immagini pubblicate invece di compilarle |

Dopo aver modificato `.env`, ricrea: `docker compose up -d`.

### Pubblicare le immagini

```bash
docker compose build
docker tag achernar-observer-backend:latest ghcr.io/<user>/observer-backend:<versao>
docker tag achernar-observer-frontend:latest ghcr.io/<user>/observer-frontend:<versao>
docker push ghcr.io/<user>/observer-backend:<versao>
docker push ghcr.io/<user>/observer-frontend:<versao>
```

Chi esegue senza compilare, imposti in `.env`:

```env
BACKEND_IMAGE=ghcr.io/<user>/observer-backend:<versao>
FRONTEND_IMAGE=ghcr.io/<user>/observer-frontend:<versao>
```

e avvii con `docker compose up -d` (senza `--build`).

## Collegare LiteLLM

Riepilogo — il passo passo completo (con `config.yaml`, `.env` e `docker-compose.yml` pronti da copiare) è nel **[MANUAL.md](docs/MANUAL.it.md)**:

1. Nel `config.yaml` di LiteLLM, attiva il callback OTEL:
   ```yaml
   litellm_settings:
     callbacks:
       - otel
   ```
2. Nel `.env` di LiteLLM, punta all'Observer:
   ```env
   OTEL_EXPORTER=otlp_http
   OTEL_ENDPOINT=http://host.docker.internal:4318
   OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
   ```
   (Se LiteLLM gira fuori da Docker, usa `http://localhost:4318`.)
3. Riavvia LiteLLM e punta il tuo client a `http://localhost:4000/v1`.

## Sviluppo

Prerequisito: percorso felice (`docker compose up -d --build`) validato prima della pubblicazione.

```bash
# Tutto nel compose + debug remoto Java (collega l'IDE su localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Oppure: solo infra nel compose + app fuori (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## Risoluzione dei problemi

| Sintomo | Causa probabile |
|---|---|
| Pannello vuoto dopo aver usato l'agente | `- otel` mancante nei callback, `OTEL_ENDPOINT` errato o `extra_hosts` assente su Linux |
| `host.docker.internal` non risolve | LiteLLM fuori da Docker → usa `http://localhost:4318` |
| Porta già in uso | Regola la variabile corrispondente in `.env` e ricrea |
| Modificato `config.yaml` senza effetto | LiteLLM legge lo yaml solo all'avvio — `docker compose down && docker compose up -d` nella sua cartella |

Guida completa con log e verifica: **[MANUAL.md §8](docs/MANUAL.it.md#8-problemi-comuni)**.

## Privacy e sicurezza

- Con `SPAN_AND_EVENT`, **prompt e risposte sono salvati nel database locale** — possono includere codice sorgente e dati sensibili. Usa in un ambiente locale e affidabile.
- Non esporre le porte in rete senza necessità; l'uso predefinito è su `localhost`.
- Le chiavi API restano **solo** nel `.env` di LiteLLM. Non committare mai quel file.

## Struttura del repository

```text
.
├── backend/               # Spring Boot — ingestione OTLP + API + SSE
├── frontend/              # Angular — pannello
├── otel/                  # Config dell'OpenTelemetry Collector
├── docker-compose.yml     # Orchestrazione (percorso felice)
├── docker-compose.dev.yml # Override di debug/hot-reload
├── .env.example           # Tutte le variabili configurabili
├── docs/                  # Guide passo passo per principianti
│   ├── MANUAL.md          # Versione inglese (documentazione canonica)
│   ├── MANUAL.pt-BR.md    # Versione in portoghese brasiliano
│   ├── MANUAL.es.md       # Versione spagnola
│   ├── MANUAL.fr.md       # Versione francese
│   └── MANUAL.it.md       # Questa guida (versione italiana)
├── README.md              # Panoramica tecnica in inglese (documentazione canonica)
├── README.pt-BR.md        # Versione in portoghese brasiliano
├── README.es.md           # Versione in spagnolo
├── README.fr.md           # Versione in francese
└── README.it.md           # Questo file (panoramica tecnica in italiano)
```

## Roadmap

- [ ] Aggregazione dedicata di metriche e log OTLP (oggi accettati, senza aggregazione)
- [ ] Plugin OpenCode/ACHERNAR che invia eventi a `/api/events` (correlazione di `PROJECT.yaml`, agent, subagent, MCP e tool con gli span LLM)
