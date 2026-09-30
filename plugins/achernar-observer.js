/**
 * ACHERNAR Observer plugin for OpenCode V2.
 * Copy to .opencode/plugins/achernar-observer.js or ~/.config/opencode/plugins/.
 * It forwards OpenCode's semantic events; failures never interrupt OpenCode.
 *
 * Project resolution order (first hit wins):
 *   1. ACHERNAR_PROJECT  — process.env or .env (explicit override)
 *   2. PROJECT.yaml      — auto-detected walking up from cwd and from this plugin
 *   3. undefined         — backend falls back (directory, session, default)
 */
import { existsSync, readFileSync } from "node:fs"
import { dirname, join, parse } from "node:path"
import { fileURLToPath } from "node:url"

const DEFAULT_OBSERVER_URL = "http://localhost:18080"

function pluginDir() {
  try {
    return dirname(fileURLToPath(import.meta.url))
  } catch {
    return process.cwd()
  }
}

/** OpenCode does not always export the project .env into the server process,
 *  so the plugin reads it itself instead of silently using the wrong port. */
function readDotEnv(startDir) {
  const { root } = parse(startDir)
  const out = {}
  for (let dir = startDir; ; dir = dirname(dir)) {
    const file = join(dir, ".env")
    if (existsSync(file)) {
      for (const line of readFileSync(file, "utf8").split("\n")) {
        const m = line.match(/^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*)\s*$/i)
        if (!m) continue
        out[m[1]] = m[2].replace(/^["']|["']$/g, "")
      }
      break
    }
    if (dir === root) break
  }
  return out
}

const envFile = readDotEnv(pluginDir())

/** Conservative `project.name` extraction (mirrors backend AchernarContextResolver):
 *  standalone `project:` line + indented `name:` child. Plain prose never matches. */
function parseProjectName(text) {
  const lines = String(text || "").replace(/\r/g, "").split("\n")
  for (let i = 0; i < lines.length; i++) {
    const header = lines[i].replace(/^\s*\d+\s*:\s?/, "").trim()
    if (!/^project:\s*(#.*)?$/.test(header) && !/^.*["'{[:,]project:\s*(#.*)?$/.test(header)) continue
    const baseIndent = lines[i].search(/[^ \t]/)
    let name = null
    for (let j = i + 1; j < lines.length; j++) {
      const raw = lines[j].replace(/^\s*\d+\s*:\s?/, "")
      if (/^\s*(#|$)/.test(raw)) continue
      const indent = raw.search(/[^ \t]/)
      if (indent < 0 || indent <= baseIndent) break
      const m = raw.trim().match(/^name:\s*(.+?)\s*$/)
      if (m) {
        const candidate = cleanScalar(m[1])
        if (candidate) { name = candidate; break }
      }
    }
    if (name) return name
  }
  return undefined
}

function cleanScalar(value) {
  let clean = String(value || "").trim().replace(/["'\}\]},\]]+$/, "").trim()
  if (clean.length >= 2 && ((clean.startsWith('"') && clean.endsWith('"')) || (clean.startsWith("'") && clean.endsWith("'")))) {
    clean = clean.slice(1, -1).trim()
  }
  if (clean.length < 2 || clean.length > 200) return undefined
  if (!/[A-Za-zÀ-ÿ0-9]/.test(clean)) return undefined
  const lower = clean.toLowerCase()
  if (["...", "xxx", "todo", "fixme"].includes(lower)) return undefined
  if ((clean.startsWith("<") && clean.endsWith(">")) || clean.includes("{{") || clean.includes("}}") || clean.includes("://")) return undefined
  if (clean.split(/\s+/).length > 8 || clean.includes(". ") || clean.endsWith(".")) return undefined
  return clean
}

/** Auto-detects `config/PROJECT.yaml` (then `PROJECT.yaml`) walking up from
 *  the given directories. Local read-only lookup; never throws. */
function readProjectYaml(startDirs) {
  for (const startDir of startDirs) {
    if (!startDir) continue
    try {
      const { root } = parse(startDir)
      for (let dir = startDir; ; dir = dirname(dir)) {
        for (const rel of ["config/PROJECT.yaml", "PROJECT.yaml"]) {
          const file = join(dir, rel)
          if (existsSync(file)) {
            try {
              const name = parseProjectName(readFileSync(file, "utf8"))
              if (name) return name
            } catch { /* malformed: keep searching upward */ }
          }
        }
        if (dir === root) break
      }
    } catch { /* unreadable path: try next candidate */ }
  }
  return undefined
}

const observerUrl = (
  process.env.ACHERNAR_OBSERVER_URL ||
  envFile.ACHERNAR_OBSERVER_URL ||
  DEFAULT_OBSERVER_URL
).replace(/\/$/, "")
const project = process.env.ACHERNAR_PROJECT
  || envFile.ACHERNAR_PROJECT
  || readProjectYaml([process.cwd(), pluginDir()])
  || undefined

async function send(event) {
  try {
    await fetch(`${observerUrl}/api/opencode/events`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ...event, project }),
      signal: AbortSignal.timeout(1500),
    })
  } catch (_) {
    // Observability must never break an agent execution.
  }
}

export default {
  id: "achernar-observer",
  async setup(ctx) {
    const controller = new AbortController()
    void (async () => {
      try {
        for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
          if (!event?.type) continue
          if (
            event.type.startsWith("session.") ||
            event.type.startsWith("message.") ||
            event.type.startsWith("tool.") ||
            event.type.includes("mcp")
          ) await send(event)
        }
      } catch (_) {
        // Abort on unload throws; observability must never break execution.
      }
    })()
    return () => controller.abort()
  },
}
