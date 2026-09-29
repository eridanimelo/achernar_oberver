<p align="center">
  <a href="MANUAL.md">🇺🇸 English</a> |
  <a href="MANUAL.pt-BR.md">🇧🇷 Português</a> |
  <a href="MANUAL.es.md">🇪🇸 Español</a> |
  <a href="MANUAL.fr.md">🇫🇷 Français</a> |
  <a href="MANUAL.it.md">🇮🇹 Italiano</a>
</p>

# MANUAL — ACHERNAR Observer

Guía paso a paso para instalar y usar ACHERNAR Observer desde cero,
aunque nunca hayas usado Docker, LiteLLM u OpenTelemetry.

## 1. Qué es y cómo funciona

ACHERNAR Observer es un panel local que muestra **todas las llamadas de IA**
que hacen tus agentes de código (modelos usados, tokens, costo estimado,
tiempo de respuesta, prompt y respuesta).

El flujo es así:

```text
OpenCode  --->  LiteLLM (:4000)  --->  DeepSeek / Gemini (nube)
                         |
                         +--> OTLP/HTTP (:4318)  --->  Observer  --->  Panel (:18180)
```

Puntos importantes:

- El Observer **solo observa**. Si está apagado, tu OpenCode + LiteLLM
  siguen funcionando con normalidad.
- LiteLLM envía la telemetría al Observer vía **OTLP/HTTP en el puerto 4318**.
- El panel web está en **http://localhost:18180**.
- Ninguna clave de API queda en el Observer. Las claves viven en LiteLLM.

## 2. Prerrequisitos

| Requisito | Cómo verificar | Cómo instalar |
|---|---|---|
| Docker + Docker Compose | `docker compose version` | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac) o `docker.io` + plugin compose (Linux) |
| Git | `git --version` | [git-scm.com](https://git-scm.com/) |
| Clave de DeepSeek y/o Gemini | — | [platform.deepseek.com](https://platform.deepseek.com/) / [aistudio.google.com](https://aistudio.google.com/) |
| OpenCode (opcional, solo para usar con agente) | `opencode --version` | [opencode.ai](https://opencode.ai/docs) |

Puertos usados en tu equipo. Si alguno está ocupado, consulta
[Cambiar los puertos](#9-cambiar-los-puertos):

| Puerto | Servicio |
|---|---|
| 18180 | Panel del Observer |
| 18080 | API del Observer |
| 4318 | Entrada OTLP (telemetría) |
| 5433 | PostgreSQL del Observer (el 5432 por defecto se evitó a propósito, casi siempre ya está ocupado) |
| 4000 | LiteLLM |
| 3000 | Langfuse propio (solo si levantas el de la sección 4.6) |

## 3. Paso 1 — Levantar el Observer

**Opción A — imágenes publicadas (sin git clone):**

```bash
mkdir achernar-observer && cd achernar-observer
curl -o docker-compose.yml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/docker-compose.hub.yml
mkdir -p otel && curl -o otel/otel-collector-config.yaml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/otel/otel-collector-config.yaml
docker compose up -d
```

**Opción B — desde el código:**

```bash
git clone <url-del-repositorio> achernar-observer
cd achernar-observer
cp .env.example .env
docker compose up -d --build
```

La primera vez tarda unos minutos (descarga las imágenes y compila).
Síguelo con:

```bash
docker compose ps
docker compose logs -f backend otel-collector
```

Está listo cuando `docker compose ps` muestre los 4 servicios corriendo:

- `postgres`
- `backend`
- `otel-collector`
- `frontend`

Abre el panel: **http://localhost:18180**

Estará vacío — es normal, aún no hay telemetría. Déjalo corriendo.

> La base de datos del Observer ya sale en el puerto 5433 a propósito, porque el
> 5432 por defecto casi siempre ya está ocupado por otro postgres. Si quieres el
> puerto por defecto, edita `.env` y define `POSTGRES_PORT=5432` antes del `up`.

## 4. Paso 2 — Levantar LiteLLM apuntando al Observer

LiteLLM puede estar en **cualquier carpeta**, fuera del repositorio del Observer.
Crea una carpeta para él:

```bash
mkdir litellm && cd litellm
```

### 4.1. Archivo `.env` de LiteLLM

Crea un archivo llamado `.env` con el contenido de abajo,
reemplazando con tus **claves reales**:

```env
# --- Claves de proveedores (pega las tuyas) ---
DEEPSEEK_API_KEY=pega-tu-clave-deepseek-aqui
GEMINI_API_KEY=pega-tu-clave-gemini-aqui

# --- Langfuse (opcional, segundo destino de la telemetría) ---
LANGFUSE_SECRET_KEY="sk-lf-tu-clave-aqui"
LANGFUSE_PUBLIC_KEY="pk-lf-tu-clave-aqui"
LANGFUSE_OTEL_HOST=http://host.docker.internal:3000

# --- Telemetría para ACHERNAR Observer ---
OTEL_EXPORTER=otlp_http
OTEL_ENDPOINT=http://host.docker.internal:4318
OTEL_SERVICE_NAME=litellm
OTEL_ENVIRONMENT_NAME=local

LITELLM_OTEL_V2=true
USE_OTEL_LITELLM_REQUEST_SPAN=true
LITELLM_OTEL_INTEGRATION_ENABLE_METRICS=true
LITELLM_OTEL_INTEGRATION_ENABLE_EVENTS=true

# Queremos observar prompt/contexto/respuesta.
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental
```

Qué hace cada bloque:

- **Proveedores**: claves usadas para llamar a DeepSeek/Gemini.
- **Langfuse**: si usas Langfuse, sigue recibiendo todo con normalidad.
  Si no lo usas, puedes quitar las líneas `LANGFUSE_*` y el `- langfuse`
  del `config.yaml` de abajo.
- **Observer**: dice a LiteLLM que envíe spans OTLP a
  `http://host.docker.internal:4318`, que es el Observer del paso 1.
  `SPAN_AND_EVENT` permite ver prompt y respuesta en el panel.

### 4.2. Archivo `config.yaml` de LiteLLM

Crea un archivo llamado `config.yaml` en la misma carpeta:

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

Detalles:

- `model_name` es el apodo que usas en OpenCode. `model:` es el modelo
  real en el proveedor. `api_key: os.environ/...` lee la clave del `.env` —
  la clave **nunca** queda escrita en el yaml.
- `callbacks: [otel, langfuse]` es lo que activa el envío de telemetría.
  Sin el `- otel`, nada llega al Observer. Si no usas Langfuse, deja solo
  el `- otel`.
- Para añadir o quitar modelos, edita solo la lista y reinicia LiteLLM.

### 4.3. Archivo `docker-compose.yml` de LiteLLM

En la misma carpeta, crea el `docker-compose.yml`:

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

> `extra_hosts` es lo que permite al contenedor ver al Observer en el puerto
> 4318 en Linux. En Mac/Windows funciona incluso sin esta línea, pero mantenerla
> no hace daño en ningún sistema.

### 4.5. Añadir modelos y claves después

Todo sigue el mismo patrón — sin misterio ni necesidad de tocar el Observer:

1. En el `.env` de LiteLLM, añade la clave del proveedor:
   ```env
   ANTHROPIC_API_KEY=pega-tu-clave-aqui
   ```
2. En `config.yaml`, añade un bloque a `model_list` copiando uno existente:
   ```yaml
   model_list:
     # ... los que ya existen ...
     - model_name: claude-sonnet-4
       litellm_params:
         model: anthropic/claude-sonnet-4-20250514
         api_key: os.environ/ANTHROPIC_API_KEY
   ```
3. Reinicia LiteLLM (sección 7) y usa el `model_name` en tu cliente
   (sección 5).

El `model:` sigue el patrón `proveedor/modelo-real` de LiteLLM
(ej.: `openai/gpt-5`, `anthropic/claude-opus-4-1`, `xai/grok-4`).
Vale para cualquier proveedor que LiteLLM soporte.

### 4.6. Langfuse propio vía git (opcional)

El `.env` del paso 4.1 ya apunta a un Langfuse en
`http://host.docker.internal:3000`. Si aún no tienes uno, levanta
el tuyo con el repositorio oficial (en cualquier carpeta, fuera del Observer):

```bash
git clone https://github.com/langfuse/langfuse.git
cd langfuse
docker compose up -d
```

Abre **http://localhost:3000**, crea tu cuenta/organización y genera las claves
en *Settings → API Keys*. Después pégalas en el `.env` de LiteLLM:

```env
LANGFUSE_SECRET_KEY="sk-lf-tu-clave-aqui"
LANGFUSE_PUBLIC_KEY="pk-lf-tu-clave-aqui"
```

y reinicia LiteLLM (sección 7). Si no quieres Langfuse, borra las líneas
`LANGFUSE_*` del `.env` y el `- langfuse` del `config.yaml`.

### 4.7. Levantar LiteLLM

```bash
docker compose up -d
docker compose logs -f litellm
```

Comprueba que responde:

```bash
curl http://localhost:4000/health
```

## 5. Paso 3 — Conectar tu cliente de IA a LiteLLM

Vale para **cualquier cliente**: OpenCode, Cline, Roo Code, Continue,
Claude Code, Copilot CLI o cualquier herramienta compatible con la API
de OpenAI. La regla es siempre la misma:

| Campo en el cliente | Valor |
|---|---|
| Endpoint / Base URL | `http://localhost:4000/v1` |
| API Key | cualquier texto (ej.: `sk-local`) — LiteLLM lo acepta sin clave maestra configurada |
| Modelo | un `model_name` de tu `config.yaml` (ej.: `deepseek-v4-flash`) |

El nombre del modelo en el cliente debe ser **idéntico** al `model_name`
del `config.yaml`. Así es como LiteLLM sabe a qué proveedor enrutar.

### 5.1. OpenCode

En el `opencode.json` del proyecto (o global, en
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

El `"model"` del final define el modelo por defecto al abrir OpenCode.

### 5.2. Cline / Roo Code (VS Code)

En *Settings → Provider*, elige **OpenAI Compatible** y rellena:

- **Base URL**: `http://localhost:4000/v1`
- **API Key**: `sk-local` (cualquier texto)
- **Model ID**: `deepseek-v4-flash` (u otro `model_name` de `config.yaml`)

### 5.3. Continue (VS Code / JetBrains)

En el `config.yaml` de Continue, añade:

```yaml
models:
  - name: DeepSeek V4 Flash (via LiteLLM)
    provider: openai
    model: deepseek-v4-flash
    apiKey: sk-local
    apiBase: http://localhost:4000/v1
```

### 5.4. Claude Code (terminal)

Claude Code permite cambiar el endpoint vía variable de entorno:

```bash
export ANTHROPIC_BASE_URL=http://localhost:4000
export ANTHROPIC_AUTH_TOKEN=sk-local
```

y usa un modelo Anthropic que exista en tu `config.yaml`
(ej.: el `claude-sonnet-4` de la sección 4.5).

### 5.5. GitHub Copilot

El Copilot de VS Code y Copilot CLI usan los modelos de tu cuenta GitHub
y **no aceptan endpoint personalizado** — no se pueden apuntar a LiteLLM.
Para un flujo estilo Copilot con tus modelos vía LiteLLM, usa uno de
los clientes de arriba (Cline, Roo Code, Continue u OpenCode).

### 5.6. Cualquier otro cliente OpenAI-compatible

Si la herramienta tiene un campo de endpoint/base URL personalizado, aplica la
tabla del inicio de esta sección. Si solo acepta la clave oficial del proveedor,
no pasa por LiteLLM — y por tanto no aparece en el Observer.

### 5.7. Plugin Observer de OpenCode (flujo del agente) + nombrar tus docs

OTLP (secciones 4–5) muestra las llamadas LLM. Para ver también el **flujo
del agente** (sesión, agent, tools, MCP) en el detalle del span, instala el
plugin del Observer para OpenCode V2. El archivo está en este repo en
`plugins/achernar-observer.js`:

```bash
# por proyecto
mkdir -p .opencode/plugins
cp /ruta/a/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

# o global (todos los proyectos)
mkdir -p ~/.config/opencode/plugins
cp /ruta/a/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
```

En el **`.env` del proyecto** (el que carga OpenCode), define:

```env
ACHERNAR_OBSERVER_URL=http://localhost:18080
ACHERNAR_PROJECT=mi-proyecto
```

Notas:

- Orden de resolución: `process.env` → `.env` (buscado desde la carpeta del
  plugin) → valor por defecto `http://localhost:18080`. Ajusta el puerto si
  cambiaste `BACKEND_PORT` en el `.env` del Observer.
- Sin `ACHERNAR_PROJECT`, el proyecto usa el nombre de la carpeta del
  directorio del evento y luego `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` por
  defecto). Usa un nombre fijo por proyecto para que el filtro agrupe bien.
- El plugin envía eventos `session.*`, `message.*`, `tool.*` y `*mcp*` a
  `POST /api/opencode/events` con timeout de 1500 ms. Si el Observer está
  caído, el agente sigue funcionando — la observabilidad nunca rompe
  la ejecución.
- Verifica: usa el agente una vez, revisa el detalle (pestañas de flujo /
  conversación / tools) y `docker compose logs -f backend`.

#### Mejor diagrama de flujo con cabecera frontmatter

Añade una cabecera YAML al inicio de tus `AGENT.md`, `SKILL.md` o archivos
de rules:

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

La pestaña de flujo usa el `name` de la cabecera (alternativa: nombre de la
carpeta), `type`/`kind` (alternativa: tipo inferido) y `description`
(visible en el detalle del nodo). Sin cabecera, nombres genéricos como
`SKILL.md` usan el nombre de la carpeta padre — sigue agrupando, pero se lee
peor.

## 6. Paso 4 — Probar de punta a punta

1. Abre OpenCode (o tu cliente de la sección 5) y elige un modelo de LiteLLM.
2. Envía `buenos días`.
3. Abre **http://localhost:18180**.
4. La llamada debe aparecer en el panel con modelo, tokens, duración
   y las pestañas de request/response.

Para seguir la ingesta en tiempo real:

```bash
# en la carpeta del Observer
docker compose logs -f otel-collector backend
```

¿Sin OpenCode? Prueba directa contra LiteLLM:

```bash
curl http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "buenos días"}]}'
```

## 7. Uso diario

| Acción | Comando (en la carpeta del Observer) |
|---|---|
| Ver estado | `docker compose ps` |
| Ver logs | `docker compose logs -f backend otel-collector` |
| Detener todo (conserva datos) | `docker compose down` |
| Levantar de nuevo | `docker compose up -d` |
| Limpiar panel (vía UI) | Botón **Borrar datos** en el panel |
| Borrar TODO, incluyendo la base de datos | `docker compose down -v && docker compose up -d --build` |
| Reiniciar solo LiteLLM (tras editar `config.yaml`/`.env`) | `docker compose down && docker compose up -d` (en la carpeta de LiteLLM) |

## 8. Problemas comunes

**Panel vacío tras usar OpenCode**
1. Revisa el callback: `config.yaml` debe tener `- otel` en `litellm_settings.callbacks`.
2. Revisa el endpoint: el `.env` de LiteLLM debe tener `OTEL_ENDPOINT=http://host.docker.internal:4318`.
3. En Linux, revisa `extra_hosts` en el compose de LiteLLM.
4. Mira los logs: `docker compose logs -f otel-collector backend` (en la carpeta del Observer).

**`host.docker.internal` no resuelve (LiteLLM fuera de Docker)**
Si LiteLLM corre directo en la máquina (sin contenedor), cambia en su `.env`:

```env
OTEL_ENDPOINT=http://localhost:4318
```

**Puerto ya en uso**
Ej.: `Bind for 0.0.0.0:4000 failed` (¿otro LiteLLM?) o conflicto en 4318.
Consulta la [sección de puertos](#9-cambiar-los-puertos). El postgres del Observer ya sale
en 5433 justo para no chocar con el de 5432.

**Cambié `config.yaml` y no pasó nada**
LiteLLM solo lee el yaml al iniciar:

```bash
# en la carpeta de LiteLLM
docker compose down && docker compose up -d
```

**Quiero observar un solo proyecto**
En el `.env` del Observer, define `OBSERVER_DEFAULT_PROJECT=mi-proyecto`
y recrea (`docker compose up -d`). No uses esto en una instancia
compartida entre proyectos — deja `UNKNOWN`.

## 9. Cambiar los puertos

Todos los puertos del Observer viven en su `.env` (copiado de `.env.example`):

```env
BACKEND_PORT=18080
FRONTEND_PORT=18180
OTLP_PORT=4318
POSTGRES_PORT=5433
```

Cambia, guarda y recrea: `docker compose up -d`. Los puertos internos de los
contenedores no cambian, así que nada más necesita ajuste.

## 10. Debug para desarrolladores

En la carpeta del Observer existe `docker-compose.dev.yml` con dos modos:

```bash
# A) Todo en compose + debug remoto Java (IDE en localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# B) Solo infra en compose + app fuera (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
OTEL_EXPORTER_ENDPOINT=http://host.docker.internal:8080/otel \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d otel-collector --no-deps
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## 11. Privacidad y seguridad

- Con `SPAN_AND_EVENT`, **prompts y respuestas se guardan en la base de datos local**.
  Esto puede incluir código fuente y datos sensibles. Úsalo en un entorno local
  y confiable.
- No expongas los puertos en la red (`0.0.0.0`) sin saber lo que haces;
  este manual asume uso en `localhost`.
- Las claves de API quedan **solo** en el `.env` de LiteLLM. Nunca hagas commit
  de ese archivo en git.
