-- Rename legacy last-server row store → last-session (server + disconnect pose).
CREATE TABLE IF NOT EXISTS player_last_session (
    uuid VARCHAR(36) PRIMARY KEY,
    server VARCHAR(128) NOT NULL,
    world VARCHAR(128),
    x DOUBLE PRECISION,
    y DOUBLE PRECISION,
    z DOUBLE PRECISION,
    yaw REAL,
    pitch REAL,
    updated_at BIGINT NOT NULL
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'player_last_server'
    ) THEN
        INSERT INTO player_last_session (uuid, server, updated_at)
        SELECT uuid, server, updated_at
        FROM player_last_server
        ON CONFLICT (uuid) DO NOTHING;
        DROP TABLE player_last_server;
    END IF;
END $$;

ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS world VARCHAR(128);
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS x DOUBLE PRECISION;
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS y DOUBLE PRECISION;
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS z DOUBLE PRECISION;
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS yaw REAL;
ALTER TABLE player_last_session ADD COLUMN IF NOT EXISTS pitch REAL;
