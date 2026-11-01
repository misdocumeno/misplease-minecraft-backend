import { Player } from '../entities/Player'
import { PlayerRepository } from '../repositories/PlayerRepository'
import { PasswordHasher } from './PasswordHasher'
import { UUIDGenerator } from './UUIDGenerator'

export class AuthService {
    constructor(
        private readonly playerRepo: PlayerRepository,
        private readonly hasher: PasswordHasher,
        private readonly uuids: UUIDGenerator,
    ) {}

    async validateCredentials(username: string, password: string) {
        const player = await this.playerRepo.findByUsername(username)
        return player !== null && (await this.hasher.compare(player.passwordHash, password)) ? player.uuid : null
    }

    async register(username: string, password: string) {
        const uuid = await this.uuids.fromUsername(username)
        const hash = await this.hasher.hash(password)
        const player = new Player(uuid, username, hash)
        await this.playerRepo.save(player)
        return uuid
    }
}
