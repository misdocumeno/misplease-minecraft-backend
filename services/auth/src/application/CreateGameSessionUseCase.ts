import { SessionStore } from '@/domain/ports/SessionStore'
import { AuthService } from '@/domain/services/AuthService'
import { logEvent } from '@/infra/log/logger'

export class CreateGameSessionUseCase {
    constructor(private authService: AuthService, private sessionStore: SessionStore) {}

    async execute(username: string, password: string, loginId: string) {
        const uuid = await this.authService.validateCredentials(username, password)
        if (uuid === null) {
            // Never log the password or any hash: only the outcome and the id.
            logEvent('info', 'session.login', {
                username,
                loginId,
                result: 'InvalidCredentials',
            })
            return null
        }
        await this.sessionStore.create(username, loginId)
        logEvent('info', 'session.login', { username, loginId, result: 'Success' })
        return uuid
    }
}
