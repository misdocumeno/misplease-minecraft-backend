CREATE TABLE IF NOT EXISTS player_last_server (
    uuid VARCHAR(36) PRIMARY KEY,
    server VARCHAR(128) NOT NULL,
    updated_at BIGINT NOT NULL
);

INSERT INTO player_last_server (uuid, server, updated_at)
SELECT uuid, server, updated_at
FROM player_last_session
ON CONFLICT (uuid) DO NOTHING;

DROP TABLE IF EXISTS player_last_session;
