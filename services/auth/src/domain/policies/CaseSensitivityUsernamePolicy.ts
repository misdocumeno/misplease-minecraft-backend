import { PlayerRepository } from '../repositories/PlayerRepository'

export type UsernameCaseCheckResult =
    | { type: 'NotFound' }
    | { type: 'ExactMatch' }
    | { type: 'WrongCase'; correctUsername: string }

export class CaseSensitivityUsernamePolicy {
    constructor(private readonly playerRepo: PlayerRepository) {}

    async check(username: string): Promise<UsernameCaseCheckResult> {
        const exact = (await this.playerRepo.findByUsername(username)) !== null

        if (exact) {
            return { type: 'ExactMatch' }
        }

        const differentCase = await this.playerRepo.findByUsernameCaseInsensitive(username)

        return differentCase ? { type: 'WrongCase', correctUsername: differentCase.username } : { type: 'NotFound' }
    }
}
