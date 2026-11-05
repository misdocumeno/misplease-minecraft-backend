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

export async function downloadBytes(url: string): Promise<Buffer> {
  let lastError: Error | undefined
  for (let attempt = 0; attempt < 5; attempt += 1) {
    if (attempt > 0) await sleep(5_000 * attempt)
    const response = await fetch(url, {
      headers: requestHeaders(url, isGithubUrl(url) ? { accept: "application/octet-stream" } : {}),
      redirect: "follow",
      signal: AbortSignal.timeout(180_000),
    })
    if (response.status === 429 || response.status === 503) {
      lastError = new Error(`${response.status} while downloading ${url}`)
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
        continue
      }
      throw new Error(`download is not a jar: ${url}`)
    }
    return bytes
  }
  throw lastError ?? new Error(`download failed for ${url}`)
}
