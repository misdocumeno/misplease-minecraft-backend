import { spawn } from "node:child_process"
import { mkdir } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import * as core from "@actions/core"

const root = path.dirname(fileURLToPath(import.meta.url))

function run(command, args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env, stdio: "inherit" })
    child.on("error", reject)
    child.on("exit", (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} exited ${code}`))
    })
  })
}

const lock = core.getInput("lock", { required: true })
const out = core.getInput("out", { required: true })
const serverOut = core.getInput("server-out") || out
const cacheDir = path.resolve(core.getInput("cache-dir") || ".plugin-lock-cache")

core.info(
  `actions-cache env: token=${Boolean(process.env.ACTIONS_RUNTIME_TOKEN)} cache_url=${Boolean(process.env.ACTIONS_CACHE_URL)} results_url=${Boolean(process.env.ACTIONS_RESULTS_URL)}`,
)

await mkdir(out, { recursive: true })
await mkdir(serverOut, { recursive: true })
await mkdir(cacheDir, { recursive: true })

const env = {
  ...process.env,
  PLUGIN_LOCK_CACHE: cacheDir,
  // Preserve GITHUB_TOKEN from the job for private release assets.
}

// Prefer job PATH node (setup-node 24+) over the action runtime binary.
const args = ["--experimental-strip-types", "src/cli.ts", "download", "--lock", lock, "--out", out, "--server-out", serverOut]
await run("node", args, env)
