import type { PlayerRepository } from '@/domain/repositories/PlayerRepository'
import type { SessionStore } from '@/domain/ports/SessionStore'
import type { PostgresJsDatabase } from 'drizzle-orm/postgres-js'
import { eq } from 'drizzle-orm'
import { playerLastServer } from '@/infra/database/schema/lastServer'
import type { AppRedisClient } from '@/index'

export type OnlineInfo = {
    server: string
} | null

export type GetPlayerResult =
    | {
          type: 'Success'
          username: string
          uuid: string
          createdAt: string
          authenticated: boolean
          online: OnlineInfo
          lastServer: { server: string; updatedAt: string } | null
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
        private readonly db: PostgresJsDatabase,
    ) {}

    async execute(username: string): Promise<GetPlayerResult> {
        const record = await this.players.findRecordByUsernameCaseInsensitive(username)
        if (!record) return { type: 'NotFound' }

        const authenticated = await this.sessions.isAuthenticated(record.username)
        const online = await this.findOnline(record.username, record.uuid)
        const lastServer = await this.findLastServer(record.uuid)

        return {
            type: 'Success',
            username: record.username,
            uuid: record.uuid,
            createdAt: record.createdAt.toISOString(),
            authenticated,
            online,
            lastServer,
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

    private async findLastServer(uuid: string) {
        const rows = await this.db
            .select()
            .from(playerLastServer)
            .where(eq(playerLastServer.uuid, uuid))
            .limit(1)
        const row = rows[0]
        if (!row) return null
        return {
            server: row.server,
            updatedAt: new Date(row.updatedAt).toISOString(),
        }
    }
}
