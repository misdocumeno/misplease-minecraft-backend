import { isZip } from "./http.ts"

// Rewrites a jar with fixed zip timestamps and the original compressed bytes,
// so two builds of the same commit hash the same when only the clock differed.
const DOS_DATE = 0x21

interface Entry {
  name: Buffer
  method: number
  flag: number
  compressed: Buffer
  uncompressedSize: number
  crc: number
  externalAttributes: number
}

function findEocd(bytes: Buffer): number {
  const start = Math.max(0, bytes.length - 66_000)
  for (let offset = bytes.length - 22; offset >= start; offset -= 1) {
    if (bytes.readUInt32LE(offset) === 0x06054b50) return offset
  }
  throw new Error("jar is missing its central directory")
}

function readEntries(bytes: Buffer): Entry[] {
  const eocd = findEocd(bytes)
  const count = bytes.readUInt16LE(eocd + 10)
  let cursor = bytes.readUInt32LE(eocd + 16)
  const entries: Entry[] = []
  for (let index = 0; index < count; index += 1) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error("jar central directory is truncated")
    }
    const flag = bytes.readUInt16LE(cursor + 8)
    const method = bytes.readUInt16LE(cursor + 10)
    const crc = bytes.readUInt32LE(cursor + 16)
    const compressedSize = bytes.readUInt32LE(cursor + 20)
    const uncompressedSize = bytes.readUInt32LE(cursor + 24)
    const nameLength = bytes.readUInt16LE(cursor + 28)
    const extraLength = bytes.readUInt16LE(cursor + 30)
    const commentLength = bytes.readUInt16LE(cursor + 32)
    const externalAttributes = bytes.readUInt32LE(cursor + 38)
    const localOffset = bytes.readUInt32LE(cursor + 42)
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength)
    if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff) {
      throw new Error(`jar entry ${name.toString()} uses zip64`)
    }
    if (bytes.readUInt32LE(localOffset) !== 0x04034b50) {
      throw new Error(`jar entry ${name.toString()} is missing its local header`)
    }
    const localNameLength = bytes.readUInt16LE(localOffset + 26)
    const localExtraLength = bytes.readUInt16LE(localOffset + 28)
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength
    entries.push({
      name,
      method,
      flag: flag & ~0x9,
      compressed: bytes.subarray(dataOffset, dataOffset + compressedSize),
      uncompressedSize,
      crc,
      externalAttributes,
    })
    cursor += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

function writeEntries(entries: Entry[]): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const entry of entries) {
    const local = Buffer.alloc(30 + entry.name.length)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(entry.flag, 6)
    local.writeUInt16LE(entry.method, 8)
    local.writeUInt16LE(0, 10)
    local.writeUInt16LE(DOS_DATE, 12)
    local.writeUInt32LE(entry.crc, 14)
    local.writeUInt32LE(entry.compressed.length, 18)
    local.writeUInt32LE(entry.uncompressedSize, 22)
    local.writeUInt16LE(entry.name.length, 26)
    entry.name.copy(local, 30)
    locals.push(local, entry.compressed)

    const central = Buffer.alloc(46 + entry.name.length)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(entry.flag, 8)
    central.writeUInt16LE(entry.method, 10)
    central.writeUInt16LE(0, 12)
    central.writeUInt16LE(DOS_DATE, 14)
    central.writeUInt32LE(entry.crc, 16)
    central.writeUInt32LE(entry.compressed.length, 20)
    central.writeUInt32LE(entry.uncompressedSize, 24)
    central.writeUInt16LE(entry.name.length, 28)
    central.writeUInt32LE(entry.externalAttributes, 38)
    central.writeUInt32LE(offset, 42)
    entry.name.copy(central, 46)
    centrals.push(central)
    offset += local.length + entry.compressed.length
  }
  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(centralSize, 12)
  eocd.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, ...centrals, eocd])
}

export function zipEntryNames(bytes: Buffer): string[] {
  if (!isZip(bytes)) return []
  return readEntries(bytes).map((entry) => entry.name.toString("utf8"))
}

export function normalizeJar(bytes: Buffer): Buffer {
  if (!isZip(bytes)) throw new Error("build did not produce a jar")
  return writeEntries(readEntries(bytes))
}
