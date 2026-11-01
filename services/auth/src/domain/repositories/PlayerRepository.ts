import { Player } from '../entities/Player'

export interface PlayerRepository {
    findByUsername(username: string): Promise<Player | null>
    findByUsernameCaseInsensitive(username: string): Promise<Player | null>
    findByUuid(uuid: string): Promise<Player | null>
    save(player: Player): Promise<void>
}
