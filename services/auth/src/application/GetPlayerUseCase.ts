import type { PlayerRepository } from '@/domain/repositories/PlayerRepository'
import type { SessionStore } from '@/domain/ports/SessionStore'
import type { PostgresJsDatabase } from 'drizzle-orm/postgres-js'
import { eq } from 'drizzle-orm'
import { playerLastSession } from '@misplease/db'
import type { AppRedisClient } from '@/index'

export type OnlineInfo = {
    server: string
} | null

export type LastSessionInfo = {
    server: string
    world: string | null
    x: number | null
    y: number | null
    z: number | null
    yaw: number | null
    pitch: number | null
    updatedAt: string
}

export type GetPlayerResult =
    | {
          type: 'Success'
          username: string
          uuid: string
          createdAt: string
          authenticated: boolean
          online: OnlineInfo
          lastSession: LastSessionInfo | null
      }
    | { type: 'NotFound' }

type PresencePayload = {
    players?: Array<{ username: string; uuid: string; server: string }>
}

export class GetPlayerUseCase {
    constructor(
        private readonly players: PlayerRepository,
        private readonly sessions: SessionStore,
        private readonly redis: AppRedisClient,
        // Schema is owned by @misplease/db; auth only needs query capability.
        private readonly db: PostgresJsDatabase<Record<string, unknown>>,
    ) {}

    async execute(username: string): Promise<GetPlayerResult> {
        const record = await this.players.findRecordByUsernameCaseInsensitive(username)
        if (!record) return { type: 'NotFound' }

        const authenticated = await this.sessions.isAuthenticated(record.username)
        const online = await this.findOnline(record.username, record.uuid)
        const lastSession = await this.findLastSession(record.uuid)

        return {
            type: 'Success',
            username: record.username,
            uuid: record.uuid,
            createdAt: record.createdAt.toISOString(),
            authenticated,
            online,
            lastSession,
        }
    }

    private async findOnline(username: string, uuid: string): Promise<OnlineInfo> {
        try {
            const raw = await this.redis.get('misplease:presence:proxy')
            if (!raw) return null
            const presence = JSON.parse(raw) as PresencePayload
            const hit = presence.players?.find(
                (p) => p.uuid === uuid || p.username.toLowerCase() === username.toLowerCase(),
            )
            return hit ? { server: hit.server } : null
        } catch {
            return null
        }
    }

    private async findLastSession(uuid: string): Promise<LastSessionInfo | null> {
        const rows = await this.db
            .select()
            .from(playerLastSession)
            .where(eq(playerLastSession.uuid, uuid))
            .limit(1)
        const row = rows[0]
        if (!row) return null
        return {
            server: row.server,
            world: row.world ?? null,
            x: row.x ?? null,
            y: row.y ?? null,
            z: row.z ?? null,
            yaw: row.yaw ?? null,
            pitch: row.pitch ?? null,
            updatedAt: new Date(row.updatedAt).toISOString(),
        }
    }
}
