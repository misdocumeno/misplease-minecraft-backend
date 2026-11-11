import type { SessionStore } from '@/domain/ports/SessionStore'
import type { AppRedisClient } from '@/index'

export type OnlinePlayerRow = {
    username: string
    uuid: string
    server: string
    authenticated: boolean
}

type PresencePayload = {
    players?: Array<{ username: string; uuid: string; server: string }>
}

export class ListOnlinePlayersUseCase {
    constructor(
        private readonly redis: AppRedisClient,
        private readonly sessions: SessionStore,
    ) {}

    async execute(): Promise<OnlinePlayerRow[]> {
        const raw = await this.redis.get('misplease:presence:proxy')
        if (!raw) return []
        let presence: PresencePayload
        try {
            presence = JSON.parse(raw) as PresencePayload
        } catch {
            return []
        }
        const players = presence.players ?? []
        const rows: OnlinePlayerRow[] = []
        for (const p of players) {
            const authenticated = await this.sessions.isAuthenticated(p.username)
            rows.push({
                username: p.username,
                uuid: p.uuid,
                server: p.server,
                authenticated,
            })
        }
        return rows
    }
}
