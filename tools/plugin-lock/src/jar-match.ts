/**
 * Pick a jar filename.
 * - omit selector when there is exactly one candidate
 * - plain string → exact name match (no regex escaping)
 * - `/pattern/flags` → RegExp (YAML has no regex type; this is the convention)
 */
export function matchJar(candidates: string[], selector: string | undefined, context: string): string {
  if (!selector) {
    if (candidates.length === 1) return candidates[0]
    throw new Error(`${context} has ${candidates.length} jars; set jar: to an exact name or /regex/; jars: ${candidates.join(", ") || "none"}`)
  }

  const regex = parseRegexLiteral(selector)
  const hits = regex
    ? candidates.filter((name) => regex.test(name))
    : candidates.filter((name) => name === selector)

  if (hits.length === 1) return hits[0]
  if (hits.length === 0) {
    throw new Error(`${context} has no jar matching ${selector}; jars: ${candidates.join(", ") || "none"}`)
  }
  throw new Error(`${context} jar ${selector} matched ${hits.length} files: ${hits.join(", ")}`)
}

function parseRegexLiteral(value: string): RegExp | undefined {
  if (!value.startsWith("/")) return undefined
  const last = value.lastIndexOf("/")
  if (last <= 0) return undefined
  const body = value.slice(1, last)
  const flags = value.slice(last + 1)
  if (!/^[gimsuy]*$/.test(flags)) return undefined
  try {
    return new RegExp(body, flags)
  } catch {
    throw new Error(`invalid jar regex: ${value}`)
  }
}
