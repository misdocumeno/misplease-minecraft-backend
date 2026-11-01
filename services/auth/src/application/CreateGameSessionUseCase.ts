import { SessionStore } from '@/domain/ports/SessionStore'
import { AuthService } from '@/domain/services/AuthService'

export class CreateGameSessionUseCase {
    constructor(private authService: AuthService, private sessionStore: SessionStore) {}

    async execute(username: string, password: string) {
        const uuid = await this.authService.validateCredentials(username, password)
        if (uuid === null) return null
        await this.sessionStore.create(username)
        return uuid
    }
}
