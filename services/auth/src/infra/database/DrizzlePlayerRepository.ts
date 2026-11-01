import type { PostgresJsDatabase } from 'drizzle-orm/postgres-js'
import { eq, sql } from 'drizzle-orm'
import { PlayerRepository } from '@/domain/repositories/PlayerRepository'
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

    async save(player: Player) {
        await this.db.insert(players).values(player)
    }
}
