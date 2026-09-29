<p align="center">
  <a href="README.md">🇺🇸 English</a> |
  <a href="README.pt-BR.md">🇧🇷 Português</a> |
  <a href="README.es.md">🇪🇸 Español</a> |
  <a href="README.fr.md">🇫🇷 Français</a> |
  <a href="README.it.md">🇮🇹 Italiano</a>
</p>

# ACHERNAR Observer

Observabilité locale pour les applications exploitant les LLM — modèle, tokens, coût estimé, latence, prompt et réponse — via LiteLLM + OpenTelemetry.

> Installation à partir de zéro ? Suivez le **[MANUAL.md](docs/MANUAL.fr.md)** (Observer + LiteLLM + OpenCode, guide pas à pas pour débutants).

## Sommaire

- [Pourquoi l'utiliser](#pourquoi-lutiliser)
- [Fonctionnement](#fonctionnement)
- [Prérequis](#prérequis)
- [Démarrage rapide](#démarrage-rapide)
- [Configuration](#configuration)
- [Connecter LiteLLM](#connecter-litellm)
- [Plugin OpenCode (flux de l'agent)](#plugin-opencode-flux-de-lagent)
- [Développement](#développement)
- [Dépannage](#dépannage)
- [Confidentialité et sécurité](#confidentialité-et-sécurité)
- [Structure du dépôt](#structure-du-dépôt)
- [Roadmap](#roadmap)

## Pourquoi l'utiliser

- **Voyez chaque appel LLM** effectué par vos agents de code, dans un tableau de bord local.
- **Comprenez coût et performance** : tokens (dont cache), durée et modèle par appel.
- **Inspectez les request/response** formatées pour déboguer vos prompts.
- **Temps réel** : mises à jour via Server-Sent Events (SSE), sans rechargement manuel.
- **Non intrusif** : l'Observer ne fait qu'observer. En cas de panne, le flux OpenCode → LiteLLM → LLM continue de fonctionner.

## Fonctionnement

```text
OpenCode  --->  LiteLLM (:4000)  --->  LLM (cloud)
                          |
                          +--> OTLP/HTTP (:4318)  --->  Observer  --->  Tableau de bord (:18180)
```

| Composant | Technologie |
|---|---|
| Backend | Java + Spring Boot (ingestion OTLP `/otel/v1/*`, API `/api/*`, SSE `/api/stream`) |
| Frontend | Angular + Nginx (tableau de bord sur `http://localhost:18180`) |
| Télémétrie | OpenTelemetry Collector (OTLP/HTTP sur `:4318`) |
| Base de données | PostgreSQL 17 + JSONB (spans et événements complets préservés) |

Les détails d'ingestion (normalisation GenAI, priorité de projet, séparation des spans LLM vs. HTTP/auth) sont documentés dans le code du backend.

## Prérequis

- [Docker + Docker Compose](https://www.docker.com/products/docker-desktop/) (`docker compose version`)
- [Git](https://git-scm.com/) (`git --version`)
- Clé d'au moins un fournisseur LLM (ex. : DeepSeek, Gemini) — elle reste **uniquement** dans LiteLLM, jamais dans l'Observer.

Ports par défaut sur l'hôte (ajustables via `.env`) :

| Port | Service |
|---|---|
| 18180 | Tableau de bord web |
| 18080 | API de l'Observer |
| 4318 | Entrée OTLP (télémétrie) |
| 5433 | PostgreSQL (5432 délibérément évité — presque toujours occupé) |

## Démarrage rapide

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Ouvrez **http://localhost:18180**. Ce sera vide — c'est normal, il n'y a pas encore de télémétrie.

Test de bout en bout (avec LiteLLM déjà configuré) :

1. Dans votre client (ex. : OpenCode), choisissez un modèle LiteLLM et envoyez `bonjour`.
2. L'appel apparaît dans le tableau de bord avec modèle, tokens, durée et onglets request/response.

Pour suivre l'ingestion :

```bash
docker compose logs -f otel-collector backend
```

Commandes du quotidien :

| Action | Commande |
|---|---|
| Arrêter (conserve les données) | `docker compose down` |
| Redémarrer | `docker compose up -d` |
| Tout effacer, base de données incluse | `docker compose down -v && docker compose up -d --build` |
| Effacer uniquement les traces | Bouton **Effacer les données** dans le tableau de bord |

## Configuration

Toutes les options se trouvent dans `.env` (copié depuis `.env.example`) et ont des valeurs par défaut raisonnables dans le compose — `.env` n'est obligatoire que si vous sortez des valeurs standard.

| Variable | Défaut | Description |
|---|---|---|
| `FRONTEND_PORT` | `18180` | Port du tableau de bord sur l'hôte |
| `BACKEND_PORT` | `18080` | Port de l'API sur l'hôte |
| `OTLP_PORT` | `4318` | Port OTLP/HTTP sur l'hôte |
| `POSTGRES_PORT` | `5433` | Port de la base de données sur l'hôte |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `achernar_observer` / `achernar` / `achernar` | Identifiants de la base locale |
| `OBSERVER_DEFAULT_PROJECT` | `UNKNOWN` | Projet par défaut pour une instance mono-projet. **Ne pas utiliser** sur une instance partagée |
| `OBSERVER_CAPTURE_PAYLOADS` | `true` | Persiste les request/response des spans |
| `OBSERVER_CAPTURE_HEADERS` | `true` | Persiste les headers quand OTEL les fournit |
| `OBSERVER_REDACT_SECRETS` | `true` | Masque les secrets avant persistance |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | `achernar-observer-backend:latest` / `achernar-observer-frontend:latest` | Pointez vers `ghcr.io/<user>/...` pour récupérer les images publiées au lieu de builder |

Après modification du `.env`, recréez : `docker compose up -d`.

### Publier les images

```bash
docker compose build
docker tag achernar-observer-backend:latest ghcr.io/<user>/observer-backend:<versao>
docker tag achernar-observer-frontend:latest ghcr.io/<user>/observer-frontend:<versao>
docker push ghcr.io/<user>/observer-backend:<versao>
docker push ghcr.io/<user>/observer-frontend:<versao>
```

Pour exécuter sans builder, définissez dans `.env` :

```env
BACKEND_IMAGE=ghcr.io/<user>/observer-backend:<versao>
FRONTEND_IMAGE=ghcr.io/<user>/observer-frontend:<versao>
```

et démarrez avec `docker compose up -d` (sans `--build`).

## Connecter LiteLLM

Résumé — le pas à pas complet (avec `config.yaml`, `.env` et `docker-compose.yml` prêts à copier) se trouve dans **[MANUAL.md](docs/MANUAL.fr.md)** :

1. Dans le `config.yaml` de LiteLLM, activez le callback OTEL :
   ```yaml
   litellm_settings:
     callbacks:
       - otel
   ```
2. Dans le `.env` de LiteLLM, pointez vers l'Observer :
   ```env
   OTEL_EXPORTER=otlp_http
   OTEL_ENDPOINT=http://host.docker.internal:4318
   OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=SPAN_AND_EVENT
   ```
   (Si LiteLLM tourne hors Docker, utilisez `http://localhost:4318`.)
3. Redémarrez LiteLLM et pointez votre client vers `http://localhost:4000/v1`.

## Plugin OpenCode (flux de l'agent)

OTLP affiche les appels LLM. Pour voir aussi le **flux de l'agent** (session, agent, tools, MCP) dans le détail du tableau de bord, installez le plugin Observer pour OpenCode V2. Le fichier se trouve dans ce dépôt sous `plugins/achernar-observer.js`.

1. Copiez-le vers votre projet ou vers la configuration globale d'OpenCode :
   ```bash
   # par projet
   mkdir -p .opencode/plugins
   cp /chemin/vers/achernar-observer/plugins/achernar-observer.js .opencode/plugins/

   # ou global (tous les projets)
   mkdir -p ~/.config/opencode/plugins
   cp /chemin/vers/achernar-observer/plugins/achernar-observer.js ~/.config/opencode/plugins/
   ```
2. Dans le **`.env` du projet** (celui chargé par OpenCode), définissez :
   ```env
   ACHERNAR_OBSERVER_URL=http://localhost:18080
   ACHERNAR_PROJECT=mon-projet
   ```
   Ordre de résolution : `process.env` → `.env` (recherché depuis le dossier du plugin) → défaut `http://localhost:18080`. Sans `ACHERNAR_PROJECT`, le projet utilise le nom du dossier du répertoire de l'événement, puis `OBSERVER_DEFAULT_PROJECT` (`UNKNOWN` par défaut).
3. Redémarrez OpenCode. Le plugin envoie les événements sémantiques (`session.*`, `message.*`, `tool.*`, `*mcp*`) vers `POST /api/opencode/events` avec un timeout de 1500 ms — si l'Observer est arrêté, l'agent continue de fonctionner normalement.

### Nommez vos agents, skills et rules (frontmatter)

L'onglet de flux affiche un bien meilleur diagramme quand les docs portent un en-tête YAML. Ajoutez ceci en haut de vos `AGENT.md`, `SKILL.md` ou fichiers de rules :

```markdown
---
name: agent-backend
type: agent
description: "Backend Developer"
---
```

Le tableau de bord utilise le `name` de l'en-tête (repli : nom du dossier), `type`/`kind` (repli : type inféré) et `description` (affichée dans le détail du nœud). Sans en-tête, les noms génériques comme `SKILL.md` ou `AGENT.md` utilisent le nom du dossier parent.

## Développement

Prérequis : chemin nominal (`docker compose up -d --build`) validé avant publication.

```bash
# Tout dans compose + debug Java à distance (attachez l'IDE sur localhost:5005)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

# Ou : seulement l'infra dans compose + app en dehors (hot-reload)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres otel-collector
cd backend && mvn spring-boot:run
cd frontend && npm start
```

## Dépannage

| Symptôme | Cause probable |
|---|---|
| Tableau de bord vide après usage de l'agent | `- otel` manquant dans les callbacks, `OTEL_ENDPOINT` incorrect ou `extra_hosts` absent sous Linux |
| `host.docker.internal` ne résout pas | LiteLLM hors Docker → utilisez `http://localhost:4318` |
| Port déjà utilisé | Ajustez la variable correspondante dans `.env` et recréez |
| `config.yaml` modifié sans effet | LiteLLM ne lit le yaml qu'au démarrage — `docker compose down && docker compose up -d` dans son dossier |

Guide complet avec logs et vérification : **[MANUAL.md §8](docs/MANUAL.fr.md#8-problèmes-courants)**.

## Confidentialité et sécurité

- Avec `SPAN_AND_EVENT`, **prompts et réponses sont stockés dans la base locale** — ils peuvent inclure du code source et des données sensibles. Utilisez dans un environnement local et de confiance.
- N'exposez pas les ports sur le réseau sans nécessité ; l'usage par défaut est sur `localhost`.
- Les clés d'API restent **uniquement** dans le `.env` de LiteLLM. Ne commitez jamais ce fichier.

## Structure du dépôt

```text
.
├── backend/               # Spring Boot — ingestion OTLP + API + SSE
├── frontend/              # Angular — tableau de bord
├── otel/                  # Config de l'OpenTelemetry Collector
├── plugins/               # Plugin OpenCode V2 (événements sémantiques → /api/opencode/events)
├── docker-compose.yml     # Orchestration (chemin nominal)
├── docker-compose.dev.yml # Surcharges debug/hot-reload
├── .env.example           # Toutes les variables configurables
├── docs/                  # Guides pas à pas pour débutants
│   ├── MANUAL.md          # Version anglaise (documentation canonique)
│   ├── MANUAL.pt-BR.md    # Version en portugais brésilien
│   ├── MANUAL.es.md       # Version espagnole
│   ├── MANUAL.fr.md       # Ce guide (version française)
│   └── MANUAL.it.md       # Version italienne
├── README.md              # Présentation technique en anglais (documentation canonique)
├── README.pt-BR.md        # Version en portugais brésilien
├── README.es.md           # Version en espagnol
├── README.fr.md           # Ce fichier (présentation technique en français)
└── README.it.md           # Version en italien
```

## Roadmap

- [ ] Agrégation dédiée des métriques et logs OTLP (acceptés aujourd'hui, sans agrégation)
- [x] Plugin OpenCode envoyant des événements sémantiques vers `POST /api/opencode/events` (corrélation session, agent, tool et MCP avec les spans LLM — voir `plugins/achernar-observer.js`)
