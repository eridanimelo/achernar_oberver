<p align="center">
  <a href="MANUAL.md">🇺🇸 English</a> |
  <a href="MANUAL.pt-BR.md">🇧🇷 Português</a> |
  <a href="MANUAL.es.md">🇪🇸 Español</a> |
  <a href="MANUAL.fr.md">🇫🇷 Français</a> |
  <a href="MANUAL.it.md">🇮🇹 Italiano</a>
</p>

# MANUAL — ACHERNAR Observer

Guide pas à pas pour installer et utiliser ACHERNAR Observer à partir de zéro,
même si vous n'avez jamais utilisé Docker, LiteLLM ou OpenTelemetry.

## 1. Présentation et fonctionnement

ACHERNAR Observer est un tableau de bord local qui affiche **tous les appels IA**
effectués par vos agents de code (modèles utilisés, tokens, coût estimé,
temps de réponse, prompt et réponse).

Le flux ressemble à ceci :

```text
OpenCode  --->  LiteLLM (:4000)  --->  DeepSeek / Gemini (cloud)
                         |
                         +--> OTLP/HTTP (:4318)  --->  Observer  --->  Tableau de bord (:18180)
```

Points importants :

- L'Observer **ne fait qu'observer**. S'il est éteint, votre OpenCode + LiteLLM
  continuent de fonctionner normalement.
- LiteLLM envoie la télémétrie à l'Observer via **OTLP/HTTP sur le port 4318**.
- Le tableau de bord web se trouve sur **http://localhost:18180**.
- Aucune clé d'API ne se trouve dans l'Observer. Les clés sont dans LiteLLM.

## 2. Prérequis

| Exigence | Comment vérifier | Comment installer |
|---|---|---|
| Docker + Docker Compose | `docker compose version` | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac) ou `docker.io` + plugin compose (Linux) |
| Git | `git --version` | [git-scm.com](https://git-scm.com/) |
| Clé DeepSeek et/ou Gemini | — | [platform.deepseek.com](https://platform.deepseek.com/) / [aistudio.google.com](https://aistudio.google.com/) |
| OpenCode (optionnel, uniquement pour usage avec agent) | `opencode --version` | [opencode.ai](https://opencode.ai/docs) |

Ports utilisés sur votre machine. Si l'un d'eux est occupé, consultez
[Changer les ports](#9-changer-les-ports) :

| Port | Service |
|---|---|
| 18180 | Tableau de bord de l'Observer |
| 18080 | API de l'Observer |
| 4318 | Entrée OTLP (télémétrie) |
| 5433 | PostgreSQL de l'Observer (le 5432 par défaut a été délibérément évité, il est presque toujours déjà occupé) |
| 4000 | LiteLLM |
| 3000 | Langfuse auto-hébergé (uniquement si vous démarrez celui de la section 4.6) |

## 3. Étape 1 — Démarrer l'Observer

**Option A — images publiées (sans git clone) :**

```bash
mkdir achernar-observer && cd achernar-observer
curl -o docker-compose.yml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/docker-compose.hub.yml
mkdir -p otel && curl -o otel/otel-collector-config.yaml https://raw.githubusercontent.com/eridanimelo/achernar_oberver/master/otel/otel-collector-config.yaml
docker compose up -d
```

**Option B — depuis le code :**

```bash
git clone <url-du-depot> achernar-observer
cd achernar-observer
cp .env.example .env
docker compose up -d --build
```

La première fois prend quelques minutes (télécharge les images et build).
Suivez avec :

```bash
docker compose ps
docker compose logs -f backend otel-collector
```

C'est prêt quand `docker compose ps` montre les 4 services en cours d'exécution :

- `postgres`
- `backend`
- `otel-collector`
- `frontend`

Ouvrez le tableau de bord : **http://localhost:18180**

Ce sera vide — c'est normal, il n'y a pas encore de télémétrie. Laissez tourner.

> La base de données de l'Observer écoute déjà sur le port 5433 à dessein, car le
> 5432 par défaut est presque toujours occupé par un autre postgres. Si vous voulez
> le port par défaut, modifiez `.env` et définissez `POSTGRES_PORT=5432` avant le `up`.

## 4. Étape 2 — Démarrer LiteLLM pointé vers l'Observer

LiteLLM peut se trouver dans **n'importe quel dossier**, hors du dépôt de l'Observer.
Créez un dossier pour lui :

```bash
mkdir litellm && cd litellm
```

### 4.1. Fichier `.env` de LiteLLM

Créez un fichier appelé `.env` avec le contenu ci-dessous,
en remplaçant par vos **vraies clés** :

```env
# --- Clés des fournisseurs (collez les vôtres) ---
DEEPSEEK_API_KEY=collez-votre-cle-deepseek-ici
GEMINI_API_KEY=collez-votre-cle-gemini-ici

# --- Langfuse (optionnel, deuxième destination de télémétrie) ---
LANGFUSE_SECRET_KEY="sk-lf-votre-cle-ici"
LANGFUSE_PUBLIC_KEY="pk-lf-votre-cle-ici"
LANGFUSE_OTEL_HOST=http://host.docker.internal:3000

# --- Télémétrie pour ACHERNAR Observer ---
OTEL_EXPORTER=otlp_http
OTEL_ENDPOINT=http://host.docker.internal:4318
OTEL_SERVICE_NAME=litellm
OTEL_ENVIRONMENT_NAME=local

LITELLM_OTEL_V2=true
USE_OTEL_LITELLM_REQUEST_SPAN=true
LITELLM_OTEL_INTEGRATION_ENABLE_METRICS=true
LITELLM_OTEL_INTEGRATION_ENABLE_EVENTS=true

# Nous voulons observer prompt/contexte/réponse.
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental
```

Ce que fait chaque bloc :

- **Fournisseurs** : clés utilisées pour appeler DeepSeek/Gemini.
- **Langfuse** : si vous utilisez Langfuse, il continue de tout recevoir normalement.
  Sinon, vous pouvez supprimer les lignes `LANGFUSE_*` et l'entrée `- langfuse`
  du `config.yaml` ci-dessous.
- **Observer** : dit à LiteLLM d'envoyer les spans OTLP vers
  `http://host.docker.internal:4318`, c'est-à-dire l'Observer de l'étape 1.
  `SPAN_AND_EVENT` permet de voir prompt et réponse dans le tableau de bord.

### 4.2. Fichier `config.yaml` de LiteLLM

Créez un fichier appelé `config.yaml` dans le même dossier :

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

Détails :

- `model_name` est le surnom que vous utilisez dans OpenCode. `model:` est le vrai
  modèle chez le fournisseur. `api_key: os.environ/...` lit la clé depuis `.env` —
  la clé n'est **jamais** écrite dans le yaml.
- `callbacks: [otel, langfuse]` est ce qui active l'envoi de télémétrie.
  Sans le `- otel`, rien n'arrive à l'Observer. Si vous n'utilisez pas Langfuse,
  gardez uniquement `- otel`.
- Pour ajouter ou retirer des modèles, modifiez simplement la liste et redémarrez LiteLLM.

### 4.3. Fichier `docker-compose.yml` de LiteLLM

Dans le même dossier, créez `docker-compose.yml` :

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

> `extra_hosts` est ce qui permet au conteneur de joindre l'Observer sur le port
> 4318 sous Linux. Sur Mac/Windows cela fonctionne même sans cette ligne, mais la
> garder ne nuit à aucun système.

### 4.5. Ajouter modèles et clés plus tard

Tout suit le même schéma — rien de sorcier, pas besoin de toucher à l'Observer :

1. Dans le `.env` de LiteLLM, ajoutez la clé du fournisseur :
   ```env
   ANTHROPIC_API_KEY=collez-votre-cle-ici
   ```
2. Dans `config.yaml`, ajoutez un bloc à `model_list` en copiant un existant :
   ```yaml
   model_list:
     # ... les existants ...
     - model_name: claude-sonnet-4
       litellm_params:
         model: anthropic/claude-sonnet-4-20250514
         api_key: os.environ/ANTHROPIC_API_KEY
   ```
3. Redémarrez LiteLLM (section 7) et utilisez le `model_name` dans votre client
   (section 5).

Le `model:` suit le schéma LiteLLM `fournisseur/vrai-modèle`
(ex. : `openai/gpt-5`, `anthropic/claude-opus-4-1`, `xai/grok-4`).
Valable pour tout fournisseur supporté par LiteLLM.

### 4.6. Langfuse auto-hébergé via git (optionnel)

Le `.env` de l'étape 4.1 pointe déjà vers un Langfuse sur
`http://host.docker.internal:3000`. Si vous n'en avez pas encore, démarrez
le vôtre avec le dépôt officiel (dans n'importe quel dossier, hors Observer) :

```bash
git clone https://github.com/langfuse/langfuse.git
cd langfuse
docker compose up -d
```

Ouvrez **http://localhost:3000**, créez votre compte/organisation et générez les clés
dans *Settings → API Keys*. Puis collez-les dans le `.env` de LiteLLM :

```env
LANGFUSE_SECRET_KEY="sk-lf-votre-cle-ici"
LANGFUSE_PUBLIC_KEY="pk-lf-votre-cle-ici"
```

et redémarrez LiteLLM (section 7). Si vous ne voulez pas de Langfuse, supprimez les
lignes `LANGFUSE_*` du `.env` et `- langfuse` du `config.yaml`.

### 4.7. Démarrer LiteLLM

```bash
docker compose up -d
docker compose logs -f litellm
```

Vérifiez qu'il répond :

```bash
curl http://localhost:4000/health
```

## 5. Étape 3 — Connecter votre client IA à LiteLLM

Valable pour **n'importe quel client** : OpenCode, Cline, Roo Code, Continue,
Claude Code, Copilot CLI ou tout outil compatible avec l'API
d'OpenAI. La règle est toujours la même :

| Champ dans le client | Valeur |
|---|---|
| Endpoint / Base URL | `http://localhost:4000/v1` |
| API Key | n'importe quel texte (ex. : `sk-local`) — LiteLLM l'accepte sans clé maître configurée |
| Modèle | un `model_name` de votre `config.yaml` (ex. : `deepseek-v4-flash`) |

Le nom du modèle dans le client doit être **identique** au `model_name`
du `config.yaml`. C'est ainsi que LiteLLM sait vers quel fournisseur router.

### 5.1. OpenCode

Dans le `opencode.json` du projet (ou global, dans
`~/.config/opencode/opencode.json`) :

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

Le `"model"` final définit le modèle par défaut à l'ouverture d'OpenCode.

### 5.2. Cline / Roo Code (VS Code)

Dans *Settings → Provider*, choisissez **OpenAI Compatible** et renseignez :

- **Base URL** : `http://localhost:4000/v1`
- **API Key** : `sk-local` (n'importe quel texte)
- **Model ID** : `deepseek-v4-flash` (ou un autre `model_name` de `config.yaml`)

### 5.3. Continue (VS Code / JetBrains)

Dans le `config.yaml` de Continue, ajoutez :

```yaml
models:
  - name: DeepSeek V4 Flash (via LiteLLM)
    provider: openai
    model: deepseek-v4-flash
    apiKey: sk-local
    apiBase: http://localhost:4000/v1
```

### 5.4. Claude Code (terminal)

Claude Code permet de changer l'endpoint via variable d'environnement :

```bash
export ANTHROPIC_BASE_URL=http://localhost:4000
export ANTHROPIC_AUTH_TOKEN=sk-local
```

et utilisez un modèle Anthropic présent dans votre `config.yaml`
(ex. : `claude-sonnet-4` de la section 4.5).

### 5.5. GitHub Copilot

Copilot VS Code et Copilot CLI utilisent les modèles de votre compte GitHub
et **n'acceptent pas d'endpoint personnalisé** — impossible de les pointer vers LiteLLM.
Pour un flux façon Copilot avec vos modèles via LiteLLM, utilisez l'un des
clients ci-dessus (Cline, Roo Code, Continue ou OpenCode).

### 5.6. Tout autre client OpenAI-compatible

Si l'outil propose un champ d'endpoint/base URL personnalisé, appliquez le
tableau du début de cette section. S'il n'accepte que la clé officielle du
fournisseur, il ne passe pas par LiteLLM — et n'apparaît donc pas dans l'Observer.

### 5.7. Plugin Observer d'OpenCode (flux de l'agent) + nommer vos docs

OTLP (sections 4–5) affiche les appels LLM. Pour voir aussi le **flux de
l'agent** (session, agent, tools, MCP) dans le détail du span, installez le
plugin Observer pour OpenCode V2. Le fichier se trouve dans ce dépôt sous
`plugins/achernar-observer.js` :

```bash
# par projet
mkdir -p .opencode/plugins
cp /chemin/vers/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

# ou global (tous les projets)
mkdir -p ~/.config/opencode/plugins
cp /chemin/vers/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
```

Dans le **`.env` du projet** (celui chargé par OpenCode), définissez :

```env
ACHERNAR_OBSERVER_URL=http://localhost:18080
ACHERNAR_PROJECT=mon-projet
```

Notes :

- Ordre de résolution : `process.env` → `.env` (recherché depuis le dossier
  du plugin) → défaut `http://localhost:18080`. Ajustez le port si vous avez
  changé `BACKEND_PORT` dans le `.env` de l'Observer.
- Sans `ACHERNAR_PROJECT`, le projet utilise le nom du dossier du répertoire
  de l'événement, puis `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` par défaut).
  Utilisez un nom fixe par projet pour un bon regroupement dans le filtre.
- Le plugin envoie les événements `session.*`, `message.*`, `tool.*` et
  `*mcp*` vers `POST /api/opencode/events` avec un timeout de 1500 ms. Si
  l'Observer est arrêté, l'agent continue de fonctionner — l'observabilité ne
  casse jamais l'exécution.
- Vérifiez : utilisez l'agent une fois, contrôlez le détail (onglets flux /
  conversation / tools) et `docker compose logs -f backend`.

#### Meilleur diagramme de flux avec un en-tête frontmatter

Ajoutez un en-tête YAML en haut de vos `AGENT.md`, `SKILL.md` ou fichiers
de rules :

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

L'onglet de flux utilise le `name` de l'en-tête (repli : nom du dossier),
`type`/`kind` (repli : type inféré) et `description` (affichée dans le détail
du nœud). Sans en-tête, les noms génériques comme `SKILL.md` utilisent le nom
du dossier parent — le regroupement reste correct, mais c'est moins lisible.

## 6. Étape 4 — Test de bout en bout

1. Ouvrez OpenCode (ou votre client de la section 5) et choisissez un modèle LiteLLM.
2. Envoyez `bonjour`.
3. Ouvrez **http://localhost:18180**.
4. L'appel doit apparaître dans le tableau de bord avec modèle, tokens, durée
   et onglets request/response.

Pour suivre l'ingestion en temps réel :

```bash
# dans le dossier de l'Observer
docker compose logs -f otel-collector backend
```

Pas d'OpenCode ? Test direct contre LiteLLM :

```bash
curl http://localhost:4000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "bonjour"}]}'
```

## 7. Usage quotidien

| Action | Commande (dans le dossier de l'Observer) |
|---|---|
| Voir le statut | `docker compose ps` |
| Voir les logs | `docker compose logs -f backend otel-collector` |
| Tout arrêter (conserve les données) | `docker compose down` |
| Redémarrer | `docker compose up -d` |
| Nettoyer le tableau de bord (via UI) | Bouton **Effacer les données** dans le tableau de bord |
| Tout effacer, base de données incluse | `docker compose down -v && docker compose up -d --build` |
| Redémarrer uniquement LiteLLM (après édition de `config.yaml`/`.env`) | `docker compose down && docker compose up -d` (dans le dossier LiteLLM) |

## 8. Problèmes courants

**Tableau de bord vide après usage d'OpenCode**
1. Vérifiez le callback : `config.yaml` doit contenir `- otel` sous `litellm_settings.callbacks`.
2. Vérifiez l'endpoint : le `.env` de LiteLLM doit contenir `OTEL_ENDPOINT=http://host.docker.internal:4318`.
3. Sous Linux, vérifiez `extra_hosts` dans le compose de LiteLLM.
4. Regardez les logs : `docker compose logs -f otel-collector backend` (dans le dossier de l'Observer).

**`host.docker.internal` ne résout pas (LiteLLM hors Docker)**
Si LiteLLM tourne directement sur la machine (sans conteneur), changez dans son `.env` :

```env
OTEL_ENDPOINT=http://localhost:4318
```

**Port déjà utilisé**
Ex. : `Bind for 0.0.0.0:4000 failed` (un autre LiteLLM ?) ou conflit sur 4318.
Consultez la [section des ports](#9-changer-les-ports). Le postgres de l'Observer utilise
déjà 5433 précisément pour éviter le conflit avec 5432.

**`config.yaml` modifié sans effet**
LiteLLM ne lit le yaml qu'au démarrage :

```bash
# dans le dossier LiteLLM
docker compose down && docker compose up -d
```

**Observer un seul projet**
Dans le `.env` de l'Observer, définissez `OBSERVER_DEFAULT_PROJECT=mon-projet`
et recréez (`docker compose up -d`). N'utilisez pas ceci sur une instance
partagée entre projets — laissez `UNKNOWN`.

## 9. Changer les ports

Tous les ports de l'Observer se trouvent dans son `.env` (copié depuis `.env.example`) :

```env
BACKEND_PORT=18080
FRONTEND_PORT=18180
OTLP_PORT=4318
POSTGRES_PORT=5433
```

Modifiez, enregistrez et recréez : `docker compose up -d`. Les ports internes des
conteneurs ne changent pas, donc rien d'autre n'a besoin d'ajustement.

## 10. Debug pour développeurs

Dans le dossier de l'Observer, il y a `docker-compose.dev.yml` avec deux modes :

```bash
# A) Tout dans compose + debug Java à distance (IDE sur localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# B) Seulement l'infra dans compose + app en dehors (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
OTEL_EXPORTER_ENDPOINT=http://host.docker.internal:8080/otel \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d otel-collector --no-deps
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## 11. Confidentialité et sécurité

- Avec `SPAN_AND_EVENT`, **prompts et réponses sont stockés dans la base locale**.
  Cela peut inclure du code source et des données sensibles. Utilisez dans un
  environnement local et de confiance.
- N'exposez pas les ports sur le réseau (`0.0.0.0`) sans savoir ce que vous faites ;
  ce manuel suppose un usage sur `localhost`.
- Les clés d'API restent **uniquement** dans le `.env` de LiteLLM. Ne commitez jamais
  ce fichier dans git.
