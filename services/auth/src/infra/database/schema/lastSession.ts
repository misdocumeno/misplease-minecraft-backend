import { pgTable, varchar, bigint, doublePrecision, real } from 'drizzle-orm/pg-core'

/** Owned by backend-last-session (+ velocity restore); auth only reads for admin get. */
export const playerLastSession = pgTable('player_last_session', {
    uuid: varchar('uuid', { length: 36 }).primaryKey(),
    server: varchar('server', { length: 128 }).notNull(),
    world: varchar('world', { length: 128 }),
    x: doublePrecision('x'),
    y: doublePrecision('y'),
    z: doublePrecision('z'),
    yaw: real('yaw'),
    pitch: real('pitch'),
    updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
})
