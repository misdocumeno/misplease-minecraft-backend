import { pgTable, varchar, bigint } from 'drizzle-orm/pg-core'

/** Owned by velocity-last-server; auth only reads for admin get. */
export const playerLastServer = pgTable('player_last_server', {
    uuid: varchar('uuid', { length: 36 }).primaryKey(),
    server: varchar('server', { length: 128 }).notNull(),
    updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
})
