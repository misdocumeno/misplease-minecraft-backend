# `misplease-auth` — auth admin CLI

Host-side CLI for the **auth service admin API** (`127.0.0.1:3000`, loopback only).
Use this instead of hand-rolled `curl` calls: registering a person, resetting a
password, or checking who the auth service considers online.

Run it on the prod/preprod VM against the stack checkout:

```bash
/home/mis/misplease/tools/auth-admin/misplease-auth online
```

## Commands

| Command | Does |
|---|---|
| `misplease-auth register <username> <password>` | creates the account (`POST /admin/register`) |
| `misplease-auth set-password <username> <password>` | changes an existing account's password |
| `misplease-auth get <username>` | looks one account up |
| `misplease-auth online` | who the auth service has as connected/authenticated |

Examples:

```bash
misplease-auth register NuevoJugador 'una-clave-larga'
misplease-auth set-password chapi 'otra-clave'
misplease-auth get MasterAngel
misplease-auth online
# {"ok":true,"code":"Success","players":[{"username":"MasterAngel","uuid":"11852b1a-...","server":"tech","authenticated":true}]}
```

## How it authenticates

It reads `AUTH_ADMIN_TOKEN` from `$MISPLEASE_ROOT/.env` (default
`/home/mis/misplease`), or from the environment if set. The token is never
printed. Overridable: `MISPLEASE_ROOT`, `AUTH_ENV_FILE`, `AUTH_ADMIN_URL`.

Because the admin port is published only on `127.0.0.1:3000`, the CLI has to run
**on the VM** (or with an SSH tunnel: `ssh -L 3000:127.0.0.1:3000 mis@<vm>` and
`AUTH_ADMIN_URL=http://127.0.0.1:3000`).

## Not the same as `list`

- `misplease-auth online` — the **auth service's** view: who has a valid session,
  which backend they were last seen on. This is the one that matters for
  "is anybody playing" and for debugging login/session problems.
- `list` (in game, or through RCON) — the **backend's** view: who that one Paper
  server currently has. `main` and `tech` each answer only for themselves.

Neither of these prints anything for LuckPerms or WorldGuard queries: those two
plugins return nothing over RCON, so their commands have to be run in game.
