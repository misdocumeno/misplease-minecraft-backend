#!/usr/bin/env node
/**
 * Migrate EssentialsX mail from userdata/*.yml into player_mail (Postgres).
 *
 *   cd tools/migrate && npm ci
 *   DATABASE_URL=postgres://misplease:…@host:5432/misplease \
 *     node essentials-mail-to-postgres.mjs /path/to/Essentials/userdata
 *
 * Policy: always migrate durable player data on cutover unless explicitly waived.
 */
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import pg from "pg";
import YAML from "yaml";

const userdataDir = process.argv[2];
const databaseUrl = process.env.DATABASE_URL;
if (!userdataDir || !databaseUrl) {
  console.error(
    "Usage: DATABASE_URL=postgres://… node essentials-mail-to-postgres.mjs <Essentials/userdata>",
  );
  process.exit(1);
}

function recipientFromFile(filePath, doc) {
  const base = path.basename(filePath, ".yml");
  const lastAccount =
    (typeof doc?.lastAccountName === "string" && doc.lastAccountName) ||
    (typeof doc?.["last-account-name"] === "string" && doc["last-account-name"]) ||
    null;
  if (lastAccount) return lastAccount;
  if (/^[0-9a-f-]{36}$/i.test(base)) return null;
  return base;
}

function parseLegacyMessage(raw) {
  const idx = raw.indexOf(": ");
  if (idx <= 0) return { sender: "unknown", body: raw };
  return { sender: raw.slice(0, idx).trim() || "unknown", body: raw.slice(idx + 2) };
}

function extractMails(doc) {
  const mail = doc?.mail;
  if (!Array.isArray(mail)) return [];

  const out = [];
  for (const entry of mail) {
    if (typeof entry === "string") {
      const { sender, body } = parseLegacyMessage(entry);
      out.push({
        sender,
        body: body.slice(0, 512),
        createdAt: Date.now(),
        readAt: null,
      });
      continue;
    }
    if (!entry || typeof entry !== "object") continue;

    if (entry.legacy === true || (entry.message && !entry.sender)) {
      const raw = String(entry.message ?? "");
      const { sender, body } = parseLegacyMessage(raw);
      out.push({
        sender,
        body: body.slice(0, 512),
        createdAt: Number(entry.timestamp) || Date.now(),
        readAt: entry.read === true ? Number(entry.timestamp) || Date.now() : null,
      });
      continue;
    }

    const sender = String(entry.sender ?? "unknown");
    const body = String(entry.message ?? "").slice(0, 512);
    if (!body) continue;
    out.push({
      sender,
      body,
      createdAt: Number(entry.timestamp) || Date.now(),
      readAt: entry.read === true ? Date.now() : null,
    });
  }
  return out;
}

const client = new pg.Client({ connectionString: databaseUrl });
await client.connect();
await client.query(`
  CREATE TABLE IF NOT EXISTS player_mail (
    id BIGSERIAL PRIMARY KEY,
    recipient_name VARCHAR(16) NOT NULL,
    sender_name VARCHAR(16) NOT NULL,
    body VARCHAR(512) NOT NULL,
    created_at BIGINT NOT NULL,
    read_at BIGINT NULL
  )
`);

const files = fs
  .readdirSync(userdataDir)
  .filter((f) => f.endsWith(".yml"))
  .map((f) => path.join(userdataDir, f));

let inserted = 0;
let skippedFiles = 0;
let skippedDupes = 0;

for (const file of files) {
  let doc;
  try {
    doc = YAML.parse(fs.readFileSync(file, "utf8"));
  } catch (e) {
    console.warn(`skip parse ${file}: ${e.message}`);
    skippedFiles++;
    continue;
  }
  const recipient = recipientFromFile(file, doc);
  if (!recipient) {
    console.warn(`skip ${file}: no last-account-name for uuid file`);
    skippedFiles++;
    continue;
  }
  for (const m of extractMails(doc)) {
    const exists = await client.query(
      `SELECT 1 FROM player_mail
       WHERE lower(recipient_name)=lower($1)
         AND sender_name=$2 AND body=$3 AND created_at=$4
       LIMIT 1`,
      [recipient, m.sender.slice(0, 16), m.body, m.createdAt],
    );
    if (exists.rowCount > 0) {
      skippedDupes++;
      continue;
    }
    await client.query(
      `INSERT INTO player_mail (recipient_name, sender_name, body, created_at, read_at)
       VALUES (lower($1), $2, $3, $4, $5)`,
      [recipient, m.sender.slice(0, 16), m.body, m.createdAt, m.readAt],
    );
    inserted++;
  }
}

await client.end();
console.log(JSON.stringify({ inserted, skippedFiles, skippedDupes, files: files.length }, null, 2));
