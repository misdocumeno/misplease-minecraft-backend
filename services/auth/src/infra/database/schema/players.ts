import { integer, text, timestamp, uniqueIndex } from 'drizzle-orm/pg-core'
import { sql } from 'drizzle-orm'
import { auth } from '.'

export const players = auth.table(
    'players',
    {
        id: integer('id').primaryKey().generatedAlwaysAsIdentity(),
        username: text('username').notNull(),
        uuid: text('uuid').notNull(),
        passwordHash: text('password_hash').notNull(),
        createdAt: timestamp('created_at').defaultNow().notNull(),
    },
    (table) => [uniqueIndex('username_case_insensitive_idx').on(sql`lower(${table.username})`)],
)
