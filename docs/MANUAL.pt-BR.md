<p align="center">
  <a href="MANUAL.md">🇺🇸 English</a> |
  <a href="MANUAL.pt-BR.md">🇧🇷 Português</a> |
  <a href="MANUAL.es.md">🇪🇸 Español</a> |
  <a href="MANUAL.fr.md">🇫🇷 Français</a> |
  <a href="MANUAL.it.md">🇮🇹 Italiano</a>
</p>

# MANUAL — ACHERNAR Observer

Guia passo a passo para instalar e usar o ACHERNAR Observer do zero,
mesmo que você nunca tenha usado Docker, LiteLLM ou OpenTelemetry.

## 1. O que é e como funciona

O ACHERNAR Observer é um painel local que mostra **todas as chamadas de IA**
feitas pelos seus agentes de código (modelos usados, tokens, custo estimado,
tempo de resposta, prompt e resposta).

O fluxo é assim:

```text
OpenCode  --->  LiteLLM (:4000)  --->  DeepSeek / Gemini (nuvem)
                        |
                        +--> OTLP/HTTP (:4318)  --->  Observer  --->  Painel (:18180)
```

Pontos importantes:

- O Observer **só observa**. Se ele estiver desligado, seu OpenCode + LiteLLM
  continuam funcionando normalmente.
- O LiteLLM envia a telemetria para o Observer via **OTLP/HTTP na porta 4318**.
- O painel web fica em **http://localhost:18180**.
- Nenhuma chave de API fica no Observer. As chaves moram no LiteLLM.

## 2. Pré-requisitos

| Requisito | Como verificar | Como instalar |
|---|---|---|
| Docker + Docker Compose | `docker compose version` | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac) ou `docker.io` + plugin compose (Linux) |
| Git | `git --version` | [git-scm.com](https://git-scm.com/) |
| Chave DeepSeek e/ou Gemini | — | [platform.deepseek.com](https://platform.deepseek.com/) / [aistudio.google.com](https://aistudio.google.com/) |
| OpenCode (opcional, só p/ usar com agente) | `opencode --version` | [opencode.ai](https://opencode.ai/docs) |

Portas usadas no seu computador. Se alguma estiver ocupada, veja a
seção [Trocar portas](#9-trocar-portas):

| Porta | Serviço |
|---|---|
| 18180 | Painel do Observer |
| 18080 | API do Observer |
| 4318 | Entrada OTLP (telemetria) |
| 5433 | PostgreSQL do Observer (a 5432 padrão foi evitada de propósito, quase sempre já está ocupada) |
| 4000 | LiteLLM |
| 3000 | Langfuse próprio (só se subir o da seção 4.6) |

## 3. Passo 1 — Subir o Observer

**Opção A — imagens publicadas (sem git clone):**

```bash
mkdir achernar-observer && cd achernar-observer
curl -o docker-compose.yml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/docker-compose.hub.yml
mkdir -p otel && curl -o otel/otel-collector-config.yaml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/otel/otel-collector-config.yaml
docker compose up -d
```

**Opção B — a partir do código:**

```bash
git clone <url-do-repositorio> achernar-observer
cd achernar-observer
cp .env.example .env
docker compose up -d --build
```

A primeira vez demora alguns minutos (baixa as imagens e compila).
Acompanhe com:

```bash
docker compose ps
docker compose logs -f backend otel-collector
```

Está pronto quando `docker compose ps` mostrar os 4 serviços rodando:

- `postgres`
- `backend`
- `otel-collector`
- `frontend`

Abra o painel: **http://localhost:18180**

Vai estar vazio — é normal, ainda não há telemetria. Deixe rodando.

> O banco do Observer já sai na porta 5433 de propósito, porque a 5432
> padrão quase sempre já está ocupada por outro postgres. Se quiser a
> padrão, edite o `.env` e defina `POSTGRES_PORT=5432` antes do `up`.

## 4. Passo 2 — Subir o LiteLLM apontando para o Observer

O LiteLLM pode ficar em **qualquer pasta**, fora do repositório do Observer.
Crie uma pasta para ele:

```bash
mkdir litellm && cd litellm
```

### 4.1. Arquivo `.env` do LiteLLM

Crie um arquivo chamado `.env` com o conteúdo abaixo,
substituindo pelas **suas chaves reais**:

```env
# --- Chaves dos provedores (cole as suas) ---
DEEPSEEK_API_KEY=cole-sua-chave-deepseek-aqui
GEMINI_API_KEY=cole-sua-chave-gemini-aqui

# --- Langfuse (opcional, segundo destino da telemetria) ---
LANGFUSE_SECRET_KEY="sk-lf-sua-chave-aqui"
LANGFUSE_PUBLIC_KEY="pk-lf-sua-chave-aqui"
LANGFUSE_OTEL_HOST=http://host.docker.internal:3000

# --- Telemetria para o ACHERNAR Observer ---
OTEL_EXPORTER=otlp_http
OTEL_ENDPOINT=http://host.docker.internal:4318
OTEL_SERVICE_NAME=litellm
OTEL_ENVIRONMENT_NAME=local

LITELLM_OTEL_V2=true
USE_OTEL_LITELLM_REQUEST_SPAN=true
LITELLM_OTEL_INTEGRATION_ENABLE_METRICS=true
LITELLM_OTEL_INTEGRATION_ENABLE_EVENTS=true

# Queremos observar prompt/contexto/resposta.
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental
```

O que cada bloco faz:

- **Provedores**: chaves usadas para chamar DeepSeek/Gemini.
- **Langfuse**: se você usa Langfuse, ele continua recebendo tudo normalmente.
  Se não usa, pode remover as linhas `LANGFUSE_*` e o `- langfuse` do
  `config.yaml` abaixo.
- **Observer**: diz ao LiteLLM para enviar spans OTLP para
  `http://host.docker.internal:4318`, que é o Observer rodando no passo 1.
  `SPAN_AND_EVENT` permite ver prompt e resposta no painel.

### 4.2. Arquivo `config.yaml` do LiteLLM

Crie um arquivo chamado `config.yaml` na mesma pasta:

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

Detalhes:

- `model_name` é o apelido que você usa no OpenCode. `model:` é o modelo
  real no provedor. `api_key: os.environ/...` lê a chave do `.env` —
  a chave **nunca** fica escrita no yaml.
- `callbacks: [otel, langfuse]` é o que ativa o envio de telemetria.
  Sem o `- otel`, nada chega ao Observer. Se não usa Langfuse, deixe só
  o `- otel`.
- Para adicionar ou remover modelos, edite só a lista e reinicie o LiteLLM.

### 4.3. Arquivo `docker-compose.yml` do LiteLLM

Na mesma pasta, crie o `docker-compose.yml`:

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

> `extra_hosts` é o que permite ao container enxergar o Observer na porta
> 4318 em Linux. No Mac/Windows funciona mesmo sem essa linha, mas mantê-la
> não faz mal em nenhum sistema.

### 4.5. Adicionar novos modelos e chaves depois

Tudo segue o mesmo padrão — não tem segredo nem precisa mexer no Observer:

1. No `.env` do LiteLLM, adicione a chave do provedor:
   ```env
   ANTHROPIC_API_KEY=cole-sua-chave-aqui
   ```
2. No `config.yaml`, adicione um bloco na `model_list` copiando um existente:
   ```yaml
   model_list:
     # ... os que já existem ...
     - model_name: claude-sonnet-4
       litellm_params:
         model: anthropic/claude-sonnet-4-20250514
         api_key: os.environ/ANTHROPIC_API_KEY
   ```
3. Reinicie o LiteLLM (seção 7) e use o `model_name` no seu cliente
   (seção 5).

O `model:` segue o padrão `provedor/modelo-real` do LiteLLM
(ex.: `openai/gpt-5`, `anthropic/claude-opus-4-1`, `xai/grok-4`).
Vale para qualquer provedor que o LiteLLM suporte.

### 4.6. Langfuse próprio via git (opcional)

O `.env` do passo 4.1 já vem apontando para um Langfuse em
`http://host.docker.internal:3000`. Se você ainda não tem um, suba o
seu próprio com o repositório oficial (em qualquer pasta, fora do Observer):

```bash
git clone https://github.com/langfuse/langfuse.git
cd langfuse
docker compose up -d
```

Abra **http://localhost:3000**, crie sua conta/organização e gere as chaves
em *Settings → API Keys*. Depois cole no `.env` do LiteLLM:

```env
LANGFUSE_SECRET_KEY="sk-lf-sua-chave-aqui"
LANGFUSE_PUBLIC_KEY="pk-lf-sua-chave-aqui"
```

e reinicie o LiteLLM (seção 7). Se não quiser Langfuse, apague as linhas
`LANGFUSE_*` do `.env` e o `- langfuse` do `config.yaml`.

### 4.7. Subir o LiteLLM

```bash
docker compose up -d
docker compose logs -f litellm
```

Teste se responde:

```bash
curl http://localhost:4000/health
```

## 5. Passo 3 — Conectar seu cliente de IA ao LiteLLM

Vale para **qualquer cliente**: OpenCode, Cline, Roo Code, Continue,
Claude Code, Copilot CLI ou qualquer ferramenta compatível com a API
da OpenAI. A regra é sempre a mesma:

| Campo no cliente | Valor |
|---|---|
| Endpoint / Base URL | `http://localhost:4000/v1` |
| API Key | qualquer texto (ex.: `sk-local`) — o LiteLLM aceita sem chave mestra configurada |
| Modelo | um `model_name` do seu `config.yaml` (ex.: `deepseek-v4-flash`) |

O nome do modelo no cliente precisa ser **idêntico** ao `model_name`
do `config.yaml`. É assim que o LiteLLM sabe para qual provedor rotear.

### 5.1. OpenCode

No `opencode.json` do projeto (ou global, em
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

O `"model"` no final define o modelo padrão ao abrir o OpenCode.

### 5.2. Cline / Roo Code (VS Code)

Em *Settings → Provider*, escolha **OpenAI Compatible** e preencha:

- **Base URL**: `http://localhost:4000/v1`
- **API Key**: `sk-local` (qualquer texto)
- **Model ID**: `deepseek-v4-flash` (ou outro `model_name` do `config.yaml`)

### 5.3. Continue (VS Code / JetBrains)

No `config.yaml` do Continue, adicione:

```yaml
models:
  - name: DeepSeek V4 Flash (via LiteLLM)
    provider: openai
    model: deepseek-v4-flash
    apiKey: sk-local
    apiBase: http://localhost:4000/v1
```

### 5.4. Claude Code (terminal)

O Claude Code permite trocar o endpoint via variável de ambiente:

```bash
export ANTHROPIC_BASE_URL=http://localhost:4000
export ANTHROPIC_AUTH_TOKEN=sk-local
```

e use um modelo Anthropic que exista no seu `config.yaml`
(ex.: o `claude-sonnet-4` da seção 4.5).

### 5.5. GitHub Copilot

O Copilot do VS Code e o Copilot CLI usam os modelos da sua conta GitHub
e **não aceitam endpoint customizado** — não dá para apontá-los ao LiteLLM.
Para ter o fluxo estilo Copilot com seus modelos via LiteLLM, use um dos
clientes acima (Cline, Roo Code, Continue ou OpenCode).

### 5.6. Qualquer outro cliente OpenAI-compatible

Se a ferramenta tem um campo de endpoint/base URL customizado, aplique a
tabela do início desta seção. Se ela só aceita chave oficial do provedor,
ela não passa pelo LiteLLM — e portanto não aparece no Observer.

### 5.7. Plugin Observer do OpenCode (fluxo do agente) + nomeando seus docs

O OTLP (seções 4–5) mostra as chamadas LLM. Para ver também o **fluxo do
agente** (sessão, agent, tools, MCP) no detalhe do span, instale o plugin do
Observer para OpenCode V2. O arquivo está neste repo em
`plugins/achernar-observer.js`:

```bash
# por projeto
mkdir -p .opencode/plugins
cp /caminho/para/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

# ou global (todos os projetos)
mkdir -p ~/.config/opencode/plugins
cp /caminho/para/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
```

No **`.env` do projeto** (o que o OpenCode carrega), defina:

```env
ACHERNAR_OBSERVER_URL=http://localhost:18080
ACHERNAR_PROJECT=meu-projeto
```

Notas:

- Ordem de resolução: `process.env` → `.env` (procurado a partir da pasta
  do plugin) → padrão `http://localhost:18080`. Ajuste a porta se você mudou
  `BACKEND_PORT` no `.env` do Observer.
- Sem `ACHERNAR_PROJECT`, o projeto cai para o nome da pasta do diretório do
  evento e depois para `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` por padrão).
  Use um nome fixo por projeto para o filtro do dashboard agrupar direito.
- O plugin envia eventos `session.*`, `message.*`, `tool.*` e `*mcp*` para
  `POST /api/opencode/events` com timeout de 1500 ms. Se o Observer estiver
  fora do ar, o agente continua funcionando — observabilidade nunca quebra
  a execução.
- Verifique: use o agente uma vez, confira o detalhe no dashboard (abas
  Fluxo / Conversa / Tools) e `docker compose logs -f backend`.

#### Diagrama de fluxo melhor com header frontmatter

Adicione um header YAML no topo dos seus `AGENT.md`, `SKILL.md` ou arquivos
de rule:

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

A aba Fluxo usa o `name` do header (fallback: nome da pasta),
`type`/`kind` (fallback: tipo inferido) e `description` (exibida no detalhe
do nó). Sem header, nomes genéricos como `SKILL.md` caem para o nome da
pasta pai — ainda agrupa, mas fica menos legível.

## 6. Passo 4 — Testar de ponta a ponta

1. Abra o OpenCode (ou seu cliente da seção 5) e escolha um modelo do LiteLLM.
2. Envie `bom dia`.
3. Abra **http://localhost:18180**.
4. A chamada deve aparecer no dashboard com modelo, tokens, duração
   e as abas de request/response.

Para acompanhar a ingestão em tempo real:

```bash
# na pasta do Observer
docker compose logs -f otel-collector backend
```

Sem OpenCode? Teste direto pelo LiteLLM:

```bash
curl http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "bom dia"}]}'
```

## 7. Uso do dia a dia

| Ação | Comando (na pasta do Observer) |
|---|---|
| Ver status | `docker compose ps` |
| Ver logs | `docker compose logs -f backend otel-collector` |
| Parar tudo (mantém dados) | `docker compose down` |
| Subir de novo | `docker compose up -d` |
| Limpar painel (via UI) | Botão **Limpar dados** no dashboard |
| Apagar TUDO, incluindo banco | `docker compose down -v && docker compose up -d --build` |
| Reiniciar só o LiteLLM (após editar `config.yaml`/`.env`) | `docker compose down && docker compose up -d` (na pasta do LiteLLM) |

## 8. Problemas comuns

**Painel vazio após usar o OpenCode**
1. Confira o callback: `config.yaml` precisa ter `- otel` em `litellm_settings.callbacks`.
2. Confira o endpoint: `.env` do LiteLLM precisa de `OTEL_ENDPOINT=http://host.docker.internal:4318`.
3. No Linux, confira o `extra_hosts` no compose do LiteLLM.
4. Veja os logs: `docker compose logs -f otel-collector backend` (na pasta do Observer).

**`host.docker.internal` não resolve (LiteLLM fora do Docker)**
Se o LiteLLM rodar direto na máquina (sem container), troque no `.env` dele:

```env
OTEL_ENDPOINT=http://localhost:4318
```

**Porta já em uso**
Ex.: `Bind for 0.0.0.0:4000 failed` (outro LiteLLM?) ou conflito na 4318.
Veja a [seção de portas](#9-trocar-portas). O postgres do Observer já sai
em 5433 justamente para não bater com o da 5432.

**Mudei `config.yaml` e nada aconteceu**
O LiteLLM só lê o yaml ao iniciar:

```bash
# na pasta do LiteLLM
docker compose down && docker compose up -d
```

**Quero observar só um projeto**
No `.env` do Observer, defina `OBSERVER_DEFAULT_PROJECT=meu-projeto`
e recrie (`docker compose up -d`). Não use isso numa instância
compartilhada entre projetos — deixe `UNKNOWN`.

## 9. Trocar portas

Todas as portas do Observer vivem no `.env` dele (copiado do `.env.example`):

```env
BACKEND_PORT=18080
FRONTEND_PORT=18180
OTLP_PORT=4318
POSTGRES_PORT=5433
```

Mude, salve e recrie: `docker compose up -d`. As portas internas dos
containers não mudam, então nada mais precisa de ajuste.

## 10. Debug para desenvolvedores

Na pasta do Observer, existe o `docker-compose.dev.yml` com dois modos:

```bash
# A) Tudo no compose + debug remoto Java (IDE em localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# B) Só infra no compose + app fora (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
OTEL_EXPORTER_ENDPOINT=http://host.docker.internal:8080/otel \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d otel-collector --no-deps
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## 11. Privacidade e segurança

- Com `SPAN_AND_EVENT`, **prompts e respostas são gravados no banco local**.
  Isso pode incluir código-fonte e dados sensíveis. Use em ambiente local
  e confiável.
- Não exponha as portas na rede (`0.0.0.0`) sem saber o que está fazendo;
  o padrão deste manual é uso em `localhost`.
- Chaves de API ficam **só** no `.env` do LiteLLM. Nunca commite esse
  arquivo no git.
