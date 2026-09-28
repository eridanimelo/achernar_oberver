<p align="center">
  <a href="README.md">🇺🇸 English</a> |
  <a href="README.pt-BR.md">🇧🇷 Português</a> |
  <a href="README.es.md">🇪🇸 Español</a> |
  <a href="README.fr.md">🇫🇷 Français</a> |
  <a href="README.it.md">🇮🇹 Italiano</a>
</p>

# ACHERNAR Observer

Observabilidad local para aplicaciones que usan LLMs — modelo, tokens, costo estimado, latencia, prompt y respuesta — vía LiteLLM + OpenTelemetry.

> ¿Instalando desde cero? Sigue el **[MANUAL.md](docs/MANUAL.es.md)** (Observer + LiteLLM + OpenCode, guía paso a paso para principiantes).

## Índice

- [Por qué usarlo](#por-qué-usarlo)
- [Cómo funciona](#cómo-funciona)
- [Prerrequisitos](#prerrequisitos)
- [Inicio rápido](#inicio-rápido)
- [Configuración](#configuración)
- [Conectando LiteLLM](#conectando-litellm)
- [Desarrollo](#desarrollo)
- [Solución de problemas](#solución-de-problemas)
- [Privacidad y seguridad](#privacidad-y-seguridad)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Roadmap](#roadmap)

## Por qué usarlo

- **Ve cada llamada LLM** que hacen tus agentes de código, en un panel local.
- **Entiende costo y rendimiento**: tokens (incl. caché), duración y modelo por llamada.
- **Inspecciona request/response** formateados para depurar prompts.
- **Tiempo real**: actualizaciones vía Server-Sent Events (SSE), sin recarga manual.
- **No intrusivo**: el Observer solo observa. Si se cae, el flujo OpenCode → LiteLLM → LLM sigue funcionando.

## Cómo funciona

```text
OpenCode  --->  LiteLLM (:4000)  --->  LLM (nube)
                          |
                          +--> OTLP/HTTP (:4318)  --->  Observer  --->  Panel (:18180)
```

| Componente | Tecnología |
|---|---|
| Backend | Java + Spring Boot (ingesta OTLP `/otel/v1/*`, API `/api/*`, SSE `/api/stream`) |
| Frontend | Angular + Nginx (panel en `http://localhost:18180`) |
| Telemetría | OpenTelemetry Collector (OTLP/HTTP en `:4318`) |
| Base de datos | PostgreSQL 17 + JSONB (spans y eventos completos preservados) |

Los detalles de ingesta (normalización GenAI, precedencia de proyecto, separación de spans LLM vs. HTTP/auth) están documentados en el código del backend.

## Prerrequisitos

- [Docker + Docker Compose](https://www.docker.com/products/docker-desktop/) (`docker compose version`)
- [Git](https://git-scm.com/) (`git --version`)
- Clave de al menos un proveedor LLM (ej.: DeepSeek, Gemini) — queda **solo** en LiteLLM, nunca en el Observer.

Puertos predeterminados en el host (ajustables vía `.env`):

| Puerto | Servicio |
|---|---|
| 18180 | Panel web |
| 18080 | API del Observer |
| 4318 | Entrada OTLP (telemetría) |
| 5433 | PostgreSQL (5432 evitado a propósito — casi siempre ocupado) |

## Inicio rápido

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Abre **http://localhost:18180**. Estará vacío — es normal, aún no hay telemetría.

Prueba de punta a punta (con LiteLLM ya configurado):

1. En tu cliente (ej.: OpenCode), elige un modelo de LiteLLM y envía `buenos días`.
2. La llamada aparece en el panel con modelo, tokens, duración y pestañas de request/response.

Para seguir la ingesta:

```bash
docker compose logs -f otel-collector backend
```

Comandos del día a día:

| Acción | Comando |
|---|---|
| Detener (conserva datos) | `docker compose down` |
| Levantar de nuevo | `docker compose up -d` |
| Borrar todo, incluyendo la base de datos | `docker compose down -v && docker compose up -d --build` |
| Limpiar solo las trazas | Botón **Borrar datos** en el panel |

## Configuración

Todas las opciones viven en `.env` (copiado de `.env.example`) y tienen valores sensatos por defecto en el compose — `.env` solo es obligatorio si te apartas del estándar.

| Variable | Default | Descripción |
|---|---|---|
| `FRONTEND_PORT` | `18180` | Puerto del panel en el host |
| `BACKEND_PORT` | `18080` | Puerto de la API en el host |
| `OTLP_PORT` | `4318` | Puerto OTLP/HTTP en el host |
| `POSTGRES_PORT` | `5433` | Puerto de la base de datos en el host |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `achernar_observer` / `achernar` / `achernar` | Credenciales de la base de datos local |
| `OBSERVER_DEFAULT_PROJECT` | `UNKNOWN` | Proyecto por defecto para instancia de proyecto único. **No usar** en instancia compartida |
| `OBSERVER_CAPTURE_PAYLOADS` | `true` | Persiste request/response de los spans |
| `OBSERVER_CAPTURE_HEADERS` | `true` | Persiste headers cuando OTEL los provee |
| `OBSERVER_REDACT_SECRETS` | `true` | Oculta secretos antes de persistir |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | `achernar-observer-backend:latest` / `achernar-observer-frontend:latest` | Apunta a `ghcr.io/<user>/...` para descargar imágenes publicadas en vez de compilar |

Tras editar `.env`, recrea: `docker compose up -d`.

### Publicar imágenes

```bash
docker compose build
docker tag achernar-observer-backend:latest ghcr.io/<user>/observer-backend:<versao>
docker tag achernar-observer-frontend:latest ghcr.io/<user>/observer-frontend:<versao>
docker push ghcr.io/<user>/observer-backend:<versao>
docker push ghcr.io/<user>/observer-frontend:<versao>
```

Quien vaya a ejecutar sin compilar, define en `.env`:

```env
BACKEND_IMAGE=ghcr.io/<user>/observer-backend:<versao>
FRONTEND_IMAGE=ghcr.io/<user>/observer-frontend:<versao>
```

y levanta con `docker compose up -d` (sin `--build`).

## Conectando LiteLLM

Resumen — el paso a paso completo (con `config.yaml`, `.env` y `docker-compose.yml` listos para copiar) está en **[MANUAL.md](docs/MANUAL.es.md)**:

1. En el `config.yaml` de LiteLLM, activa el callback OTEL:
   ```yaml
   litellm_settings:
     callbacks:
       - otel
   ```
2. En el `.env` de LiteLLM, apunta al Observer:
   ```env
   OTEL_EXPORTER=otlp_http
   OTEL_ENDPOINT=http://host.docker.internal:4318
   OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
   ```
   (Si LiteLLM corre fuera de Docker, usa `http://localhost:4318`.)
3. Reinicia LiteLLM y apunta tu cliente a `http://localhost:4000/v1`.

## Desarrollo

Prerrequisito: camino feliz (`docker compose up -d --build`) validado antes de publicar.

```bash
# Todo en compose + debug remoto Java (conecta el IDE en localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# O: solo infra en compose + app fuera (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| Panel vacío tras usar el agente | Falta `- otel` en los callbacks, `OTEL_ENDPOINT` incorrecto o `extra_hosts` ausente en Linux |
| `host.docker.internal` no resuelve | LiteLLM fuera de Docker → usa `http://localhost:4318` |
| Puerto ya en uso | Ajusta la variable correspondiente en `.env` y recrea |
| Cambiaste `config.yaml` y no pasó nada | LiteLLM solo lee el yaml al iniciar — `docker compose down && docker compose up -d` en su carpeta |

Guía completa con logs y verificación: **[MANUAL.md §8](docs/MANUAL.es.md#8-problemas-comunes)**.

## Privacidad y seguridad

- Con `SPAN_AND_EVENT`, **prompts y respuestas se guardan en la base de datos local** — pueden incluir código fuente y datos sensibles. Úsalo en un entorno local y confiable.
- No expongas los puertos a la red sin necesidad; por defecto el uso es en `localhost`.
- Las claves de API quedan **solo** en el `.env` de LiteLLM. Nunca hagas commit de ese archivo.

## Estructura del repositorio

```text
.
├── backend/               # Spring Boot — ingesta OTLP + API + SSE
├── frontend/              # Angular — panel
├── otel/                  # Config del OpenTelemetry Collector
├── docker-compose.yml     # Orquestación (camino feliz)
├── docker-compose.dev.yml # Overrides de debug/hot-reload
├── .env.example           # Todas las variables configurables
├── docs/                  # Guías paso a paso para principiantes
│   ├── MANUAL.md          # Versión en inglés (documentación canónica)
│   ├── MANUAL.pt-BR.md    # Versión en portugués brasileño
│   ├── MANUAL.es.md       # Esta guía (versión en español)
│   ├── MANUAL.fr.md       # Versión en francés
│   └── MANUAL.it.md       # Versión en italiano
├── README.md              # Visión técnica en inglés (documentación canónica)
├── README.pt-BR.md        # Versión en portugués brasileño
├── README.es.md           # Este archivo (visión técnica en español)
├── README.fr.md           # Versión en francés
└── README.it.md           # Versión en italiano
```

## Roadmap

- [ ] Agregación dedicada de métricas y logs OTLP (hoy aceptados, sin agregación)
- [ ] Plugin OpenCode/ACHERNAR enviando eventos a `/api/events` (correlación de `PROJECT.yaml`, agent, subagent, MCP y tools con spans LLM)
