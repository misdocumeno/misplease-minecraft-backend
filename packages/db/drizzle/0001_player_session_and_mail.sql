-- Idempotent: preprod may already have these from golang-migrate / SchemaUtils.
CREATE TABLE IF NOT EXISTS "player_last_session" (
	"uuid" varchar(36) PRIMARY KEY NOT NULL,
	"server" varchar(128) NOT NULL,
	"world" varchar(128),
	"x" double precision,
	"y" double precision,
	"z" double precision,
	"yaw" real,
	"pitch" real,
	"updated_at" bigint NOT NULL
);
--> statement-breakpoint
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'player_last_server'
    ) THEN
        INSERT INTO player_last_session (uuid, server, updated_at)
        SELECT uuid, server, updated_at FROM player_last_server
        ON CONFLICT (uuid) DO NOTHING;
        DROP TABLE player_last_server;
    END IF;
END $$;
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS world varchar(128);
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS x double precision;
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS y double precision;
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS z double precision;
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS yaw real;
--> statement-breakpoint
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS pitch real;
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS "player_mail" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"recipient_name" varchar(16) NOT NULL,
	"sender_name" varchar(16) NOT NULL,
	"body" varchar(512) NOT NULL,
	"created_at" bigint NOT NULL,
	"read_at" bigint
);
--> statement-breakpoint
-- Retire golang-migrate bookkeeping if present.
DROP TABLE IF EXISTS schema_migrations;
