import { SessionStore } from '@/domain/ports/SessionStore'

export class InvalidateGameSessionUseCase {
    constructor(private sessionStore: SessionStore) {}

    async execute(username: string) {
        await this.sessionStore.invalidate(username)
    }
}
