import { SessionStore } from '@/domain/ports/SessionStore'

export class IsGameSessionAuthenticatedUseCase {
    constructor(private readonly sessionStore: SessionStore) {}

    async execute(username: string) {
        return this.sessionStore.isAuthenticated(username)
    }
}
