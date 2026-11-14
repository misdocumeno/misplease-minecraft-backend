import { PlayerRepository } from '../repositories/PlayerRepository'

export type UsernameCaseCheckResult =
    | { type: 'NotFound' }
    | { type: 'ExactMatch'; username: string; uuid: string }
    | { type: 'WrongCase'; correctUsername: string; uuid: string }

export class CaseSensitivityUsernamePolicy {
    constructor(private readonly playerRepo: PlayerRepository) {}

    async check(username: string): Promise<UsernameCaseCheckResult> {
        const exact = await this.playerRepo.findByUsername(username)

        if (exact) {
            return { type: 'ExactMatch', username: exact.username, uuid: exact.uuid }
        }

        const differentCase = await this.playerRepo.findByUsernameCaseInsensitive(username)

        return differentCase
            ? { type: 'WrongCase', correctUsername: differentCase.username, uuid: differentCase.uuid }
            : { type: 'NotFound' }
    }
}
