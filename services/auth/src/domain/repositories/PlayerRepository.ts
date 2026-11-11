import { Player } from '../entities/Player'

export type PlayerRecord = {
    uuid: string
    username: string
    passwordHash: string
    createdAt: Date
}

export interface PlayerRepository {
    findByUsername(username: string): Promise<Player | null>
    findByUsernameCaseInsensitive(username: string): Promise<Player | null>
    findByUuid(uuid: string): Promise<Player | null>
    findRecordByUsernameCaseInsensitive(username: string): Promise<PlayerRecord | null>
    save(player: Player): Promise<void>
    updatePasswordHash(username: string, passwordHash: string): Promise<boolean>
}
