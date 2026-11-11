import type { PostgresJsDatabase } from 'drizzle-orm/postgres-js'
import { eq, sql } from 'drizzle-orm'
import { PlayerRepository, type PlayerRecord } from '@/domain/repositories/PlayerRepository'
import { Player } from '@/domain/entities/Player'
import { players } from './schema/players'

export class DrizzlePlayerRepository implements PlayerRepository {
    constructor(private readonly db: PostgresJsDatabase<{ players: typeof players }>) {}

    async findByUuid(uuid: string) {
        const row = await this.db.select().from(players).where(eq(players.uuid, uuid)).limit(1)
        return row[0] ? new Player(row[0].uuid, row[0].username, row[0].passwordHash) : null
    }

    async findByUsername(username: string) {
        const row = await this.db.select().from(players).where(eq(players.username, username)).limit(1)
        return row[0] ? new Player(row[0].uuid, row[0].username, row[0].passwordHash) : null
    }

    async findByUsernameCaseInsensitive(username: string) {
        const row = await this.db
            .select()
            .from(players)
            .where(eq(sql`lower(${players.username})`, username.toLowerCase()))
            .limit(1)
        return row[0] ? new Player(row[0].uuid, row[0].username, row[0].passwordHash) : null
    }

    async findRecordByUsernameCaseInsensitive(username: string): Promise<PlayerRecord | null> {
        const row = await this.db
            .select()
            .from(players)
            .where(eq(sql`lower(${players.username})`, username.toLowerCase()))
            .limit(1)
        if (!row[0]) return null
        return {
            uuid: row[0].uuid,
            username: row[0].username,
            passwordHash: row[0].passwordHash,
            createdAt: row[0].createdAt,
        }
    }

    async save(player: Player) {
        await this.db.insert(players).values(player)
    }

    async updatePasswordHash(username: string, passwordHash: string) {
        const result = await this.db
            .update(players)
            .set({ passwordHash })
            .where(eq(sql`lower(${players.username})`, username.toLowerCase()))
            .returning({ uuid: players.uuid })
        return result.length > 0
    }
}
