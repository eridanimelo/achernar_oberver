/**
 * ACHERNAR Observer plugin for OpenCode V2.
 * Copy to .opencode/plugins/achernar-observer.js or ~/.config/opencode/plugins/.
 * It forwards OpenCode's semantic events; failures never interrupt OpenCode.
 *
 * Config resolution order (first hit wins):
 *   1. process.env        — real environment / exported shell vars
 *   2. .env file          — walked up from this plugin, same process OpenCode uses
 *   3. DEFAULT            — the port the Observer actually runs on
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

const observerUrl = (
  process.env.ACHERNAR_OBSERVER_URL ||
  envFile.ACHERNAR_OBSERVER_URL ||
  DEFAULT_OBSERVER_URL
).replace(/\/$/, "")
const project = process.env.ACHERNAR_PROJECT || envFile.ACHERNAR_PROJECT || undefined

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
