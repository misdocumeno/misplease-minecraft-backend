import { createHash } from "node:crypto"

const USER_AGENT = "misplease-plugin-lock"

export type HashAlgorithm = "sha256" | "sha512" | "sha1"

export function digest(algorithm: HashAlgorithm, bytes: Buffer): string {
  return createHash(algorithm).update(bytes).digest("hex")
}

export function isZip(bytes: Buffer): boolean {
  return bytes.length > 4 && bytes[0] === 0x50 && bytes[1] === 0x4b
}

function isGithubUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname
    return host === "github.com" || host === "api.github.com" || host.endsWith(".githubusercontent.com")
  } catch {
    return false
  }
}

function requestHeaders(url: string, extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    "user-agent": USER_AGENT,
    ...extra,
  }
  if (isGithubUrl(url)) {
    const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN
    if (token) headers.authorization = `Bearer ${token}`
  }
  if (url.includes("spiget.org")) headers["spiget-user-agent"] = USER_AGENT
  return headers
}

export async function getJson<T>(url: string, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(url, {
    headers: requestHeaders(url, { accept: "application/json", ...headers }),
    signal: AbortSignal.timeout(60_000),
  })
  if (!response.ok) {
    const body = (await response.text()).slice(0, 300)
    throw new Error(`${response.status} for ${url}${body ? `: ${body}` : ""}`)
  }
  return (await response.json()) as T
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms))
}

function isSpigetUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname
    return host === "api.spiget.org" || host.endsWith(".spiget.org") || host === "cdn.spiget.org"
  } catch {
    return false
  }
}

function retryDelayMs(url: string, attempt: number, response?: Response): number {
  const retryAfter = response?.headers.get("retry-after")
  if (retryAfter) {
    const seconds = Number(retryAfter)
    if (Number.isFinite(seconds) && seconds > 0) return Math.min(seconds * 1_000, 120_000)
  }
  // Spiget/Cloudflare rate-limits GitHub Actions egress; back off harder there.
  if (isSpigetUrl(url)) return Math.min(15_000 * 2 ** attempt, 120_000)
  return 5_000 * (attempt + 1)
}

export async function downloadBytes(url: string): Promise<Buffer> {
  const attempts = isSpigetUrl(url) ? 8 : 5
  let lastError: Error | undefined
  let nextDelayMs = 0
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (nextDelayMs > 0) await sleep(nextDelayMs)
    const response = await fetch(url, {
      headers: requestHeaders(url, isGithubUrl(url) ? { accept: "application/octet-stream" } : {}),
      redirect: "follow",
      signal: AbortSignal.timeout(180_000),
    })
    if (response.status === 429 || response.status === 503) {
      lastError = new Error(`${response.status} while downloading ${url}`)
      nextDelayMs = retryDelayMs(url, attempt, response)
      continue
    }
    if (!response.ok) {
      throw new Error(`${response.status} while downloading ${url}`)
    }
    const bytes = Buffer.from(await response.arrayBuffer())
    if (!isZip(bytes)) {
      const text = bytes.slice(0, 80).toString("utf8")
      if (text.includes("error code") || text.includes("Just a moment")) {
        lastError = new Error(`download blocked while fetching ${url}`)
        nextDelayMs = retryDelayMs(url, attempt, response)
        continue
      }
      throw new Error(`download is not a jar: ${url}`)
    }
    return bytes
  }
  throw lastError ?? new Error(`download failed for ${url}`)
}
