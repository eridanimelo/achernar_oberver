<p align="center">
  <a href="README.md">🇺🇸 English</a> |
  <a href="README.pt-BR.md">🇧🇷 Português</a> |
  <a href="README.es.md">🇪🇸 Español</a> |
  <a href="README.fr.md">🇫🇷 Français</a> |
  <a href="README.it.md">🇮🇹 Italiano</a>
</p>

# ACHERNAR Observer

Observabilidade local para chamadas LLM — modelo, tokens, custo estimado, latência, prompt e resposta — via LiteLLM + OpenTelemetry.

> Instalando do zero? Siga o **[MANUAL.md](docs/MANUAL.pt-BR.md)** (Observer + LiteLLM + OpenCode, passo a passo para iniciantes).

## Índice

- [Por que usar](#por-que-usar)
- [Como funciona](#como-funciona)
- [Pré-requisitos](#pré-requisitos)
- [Início rápido](#início-rápido)
- [Configuração](#configuração)
- [Conectando o LiteLLM](#conectando-o-litellm)
- [Plugin OpenCode (fluxo do agente)](#plugin-opencode-fluxo-do-agente)
- [Desenvolvimento](#desenvolvimento)
- [Solução de problemas](#solução-de-problemas)
- [Privacidade e segurança](#privacidade-e-segurança)
- [Estrutura do repositório](#estrutura-do-repositório)
- [Roadmap](#roadmap)

## Por que usar

- **Veja cada chamada LLM** feita pelos seus agentes de código, em um painel local.
- **Entenda custo e performance**: tokens (incl. cache), duração e modelo por chamada.
- **Inspecione request/response** formatados para depurar prompts.
- **Tempo real**: atualizações via Server-Sent Events (SSE), sem refresh manual.
- **Não intrusivo**: o Observer só observa. Se cair, o fluxo OpenCode → LiteLLM → LLM continua funcionando.

## Como funciona

```text
OpenCode  --->  LiteLLM (:4000)  --->  LLM (nuvem)
                          |
                          +--> OTLP/HTTP (:4318)  --->  Observer  --->  Painel (:18180)
```

| Componente | Tecnologia |
|---|---|
| Backend | Java + Spring Boot (ingestão OTLP `/otel/v1/*`, API `/api/*`, SSE `/api/stream`) |
| Frontend | Angular + Nginx (dashboard em `http://localhost:18180`) |
| Telemetria | OpenTelemetry Collector (OTLP/HTTP em `:4318`) |
| Banco | PostgreSQL 17 + JSONB (spans e eventos completos preservados) |

Detalhes de ingestão (normalização GenAI, precedência de projeto, separação de spans LLM vs. HTTP/auth) estão documentados no código do backend.

## Pré-requisitos

- [Docker + Docker Compose](https://www.docker.com/products/docker-desktop/) (`docker compose version`)
- [Git](https://git-scm.com/) (`git --version`)
- Chave de ao menos um provedor LLM (ex.: DeepSeek, Gemini) — fica **só** no LiteLLM, nunca no Observer.

Portas padrão no host (ajustáveis via `.env`):

| Porta | Serviço |
|---|---|
| 18180 | Painel web |
| 18080 | API do Observer |
| 4318 | Entrada OTLP (telemetria) |
| 5433 | PostgreSQL (5432 evitada de propósito — quase sempre ocupada) |

## Início rápido

**Opção A — imagens publicadas (sem git clone):**

```bash
mkdir achernar-observer && cd achernar-observer
curl -o docker-compose.yml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/docker-compose.hub.yml
mkdir -p otel && curl -o otel/otel-collector-config.yaml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/otel/otel-collector-config.yaml
docker compose up -d
docker compose ps
```

**Opção B — a partir do código:**

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Abra **http://localhost:18180**. Vai estar vazio — é normal, ainda não há telemetria.

Teste de ponta a ponta (com LiteLLM já configurado):

1. No seu cliente (ex.: OpenCode), escolha um modelo do LiteLLM e envie `bom dia`.
2. A chamada aparece no dashboard com modelo, tokens, duração e abas de request/response.

Para acompanhar a ingestão:

```bash
docker compose logs -f otel-collector backend
```

Comandos do dia a dia:

| Ação | Comando |
|---|---|
| Parar (mantém dados) | `docker compose down` |
| Subir de novo | `docker compose up -d` |
| Apagar tudo, incluindo banco | `docker compose down -v && docker compose up -d --build` |
| Limpar só os traces | Botão **Limpar dados** no dashboard |

## Configuração

Todas as opções vivem no `.env` (copiado do `.env.example`) e têm defaults sensatos no compose — o `.env` só é obrigatório se você fugir do padrão.

| Variável | Default | Descrição |
|---|---|---|
| `FRONTEND_PORT` | `18180` | Porta do painel no host |
| `BACKEND_PORT` | `18080` | Porta da API no host |
| `OTLP_PORT` | `4318` | Porta OTLP/HTTP no host |
| `POSTGRES_PORT` | `5433` | Porta do banco no host |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `achernar_observer` / `achernar` / `achernar` | Credenciais do banco local |
| `OBSERVER_DEFAULT_PROJECT` | `UNKNOWN` | Fallback de projeto para instância de projeto único. **Não use** em instância compartilhada |
| `OBSERVER_CAPTURE_PAYLOADS` | `true` | Persiste request/response dos spans |
| `OBSERVER_CAPTURE_HEADERS` | `true` | Persiste headers quando o OTEL os fornece |
| `OBSERVER_REDACT_SECRETS` | `true` | Redige segredos antes de persistir |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | `achernar-observer-backend:latest` / `achernar-observer-frontend:latest` | Aponte para `eridani/achernar-observer-...` para puxar imagens publicadas em vez de buildar |

Após editar o `.env`, recrie: `docker compose up -d`.

### Publicar imagens

```bash
APP_VERSION=2.1.0
docker compose build --build-arg APP_VERSION=$APP_VERSION
docker tag achernar-observer-backend:latest eridani/achernar-observer-backend:$APP_VERSION
docker tag achernar-observer-backend:latest eridani/achernar-observer-backend:latest
docker tag achernar-observer-frontend:latest eridani/achernar-observer-frontend:$APP_VERSION
docker tag achernar-observer-frontend:latest eridani/achernar-observer-frontend:latest
docker push eridani/achernar-observer-backend:$APP_VERSION
docker push eridani/achernar-observer-backend:latest
docker push eridani/achernar-observer-frontend:$APP_VERSION
docker push eridani/achernar-observer-frontend:latest
```

Quem for rodar sem buildar, define no `.env`:

```env
BACKEND_IMAGE=eridani/achernar-observer-backend:2.1.0
FRONTEND_IMAGE=eridani/achernar-observer-frontend:2.1.0
```

e sobe com `docker compose up -d` (sem `--build`).

## Conectando o LiteLLM

Resumo — o passo a passo completo (com `config.yaml`, `.env` e `docker-compose.yml` prontos para copiar) está no **[MANUAL.md](docs/MANUAL.pt-BR.md)**:

1. No `config.yaml` do LiteLLM, ative o callback OTEL:
   ```yaml
   litellm_settings:
     callbacks:
       - otel
   ```
2. No `.env` do LiteLLM, aponte para o Observer:
   ```env
   OTEL_EXPORTER=otlp_http
   OTEL_ENDPOINT=http://host.docker.internal:4318
   OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
   ```
   (Se o LiteLLM rodar fora do Docker, use `http://localhost:4318`.)
3. Reinicie o LiteLLM e aponte seu cliente para `http://localhost:4000/v1`.

## Plugin OpenCode (fluxo do agente)

O OTLP mostra as chamadas LLM. Para ver também o **fluxo do agente** (sessão, agent, tools, MCP) no detalhe do dashboard, instale o plugin do Observer para OpenCode V2. O arquivo está neste repo em `plugins/achernar-observer.js`.

1. Copie para o seu projeto ou para a config global do OpenCode:
   ```bash
   # por projeto
   mkdir -p .opencode/plugins
   cp /caminho/para/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

   # ou global (todos os projetos)
   mkdir -p ~/.config/opencode/plugins
   cp /caminho/para/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
   ```
2. No **`.env` do projeto** (o que o OpenCode carrega), defina:
   ```env
   ACHERNAR_OBSERVER_URL=http://localhost:18080
   ACHERNAR_PROJECT=meu-projeto
   ```
   Ordem de resolução: `process.env` → `.env` (procurado a partir da pasta do plugin) → padrão `http://localhost:18080`. Sem `ACHERNAR_PROJECT`, o projeto cai para o nome da pasta do diretório do evento e depois para `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` por padrão).
3. Reinicie o OpenCode. O plugin envia eventos semânticos (`session.*`, `message.*`, `tool.*`, `*mcp*`) para `POST /api/opencode/events` com timeout de 1500 ms — se o Observer estiver fora do ar, o agente continua funcionando normalmente.

### Nomeie seus agentes, skills e rules (frontmatter)

A aba Fluxo desenha um diagrama bem melhor quando os docs têm um header YAML. Adicione no topo dos seus `AGENT.md`, `SKILL.md` ou arquivos de rule:

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

O dashboard usa o `name` do header (fallback: nome da pasta), `type`/`kind` (fallback: tipo inferido) e `description` (exibida no detalhe do nó). Sem header, nomes genéricos como `SKILL.md` ou `AGENT.md` caem para o nome da pasta pai.

## Desenvolvimento

Pré-requisito: caminho feliz (`docker compose up -d --build`) validado antes de publicar.

```bash
# Tudo no compose + debug remoto Java (anexe o IDE em localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Ou: só infra no compose + app fora (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## Solução de problemas

| Sintoma | Causa provável |
|---|---|
| Painel vazio após usar o agente | Falta `- otel` nos callbacks, `OTEL_ENDPOINT` errado ou `extra_hosts` ausente no Linux |
| `host.docker.internal` não resolve | LiteLLM fora do Docker → use `http://localhost:4318` |
| Porta já em uso | Ajuste a variável correspondente no `.env` e recrie |
| Mudou `config.yaml` e nada aconteceu | LiteLLM só lê o yaml ao iniciar — `docker compose down && docker compose up -d` na pasta dele |

Guia completo com logs e verificação: **[MANUAL.md §8](docs/MANUAL.pt-BR.md#8-problemas-comuns)**.

## Privacidade e segurança

- Com `SPAN_AND_EVENT`, **prompts e respostas são gravados no banco local** — podem incluir código-fonte e dados sensíveis. Use em ambiente local e confiável.
- Não exponha as portas na rede sem necessidade; o padrão é uso em `localhost`.
- Chaves de API ficam **só** no `.env` do LiteLLM. Nunca commite esse arquivo.

## Estrutura do repositório

```text
.
├── backend/               # Spring Boot — ingestão OTLP + API + SSE
├── frontend/              # Angular — dashboard
├── otel/                  # Config do OpenTelemetry Collector
├── plugins/               # Plugin OpenCode V2 (eventos semânticos → /api/opencode/events)
├── docker-compose.yml     # Orquestração (caminho feliz)
├── docker-compose.dev.yml # Overrides de debug/hot-reload
├── .env.example           # Todas as variáveis configuráveis
├── docs/                  # Guias passo a passo para iniciantes
│   ├── MANUAL.md          # Versão em inglês (documentação canônica)
│   ├── MANUAL.pt-BR.md    # Este guia (versão em português)
│   ├── MANUAL.es.md       # Versão em espanhol
│   ├── MANUAL.fr.md       # Versão em francês
│   └── MANUAL.it.md       # Versão em italiano
├── README.md              # Visão técnica em inglês (documentação canônica)
├── README.pt-BR.md        # Este arquivo (visão técnica em português)
├── README.es.md           # Versão em espanhol
├── README.fr.md           # Versão em francês
└── README.it.md           # Versão em italiano
```

## Roadmap

- [ ] Agregação dedicada de métricas e logs OTLP (hoje aceitos, sem agregação)
- [x] Plugin OpenCode enviando eventos semânticos para `POST /api/opencode/events` (correlação de sessão, agent, tool e MCP com spans LLM — ver `plugins/achernar-observer.js`)
