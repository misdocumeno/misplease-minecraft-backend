import { pgTable, bigserial, varchar, bigint } from 'drizzle-orm/pg-core'

/** Written/read by velocity-social mail. */
export const playerMail = pgTable('player_mail', {
    id: bigserial('id', { mode: 'number' }).primaryKey(),
    recipientName: varchar('recipient_name', { length: 16 }).notNull(),
    senderName: varchar('sender_name', { length: 16 }).notNull(),
    body: varchar('body', { length: 512 }).notNull(),
    createdAt: bigint('created_at', { mode: 'number' }).notNull(),
    readAt: bigint('read_at', { mode: 'number' }),
})
