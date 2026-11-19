import { SessionStore } from '@/domain/ports/SessionStore'

export class InvalidateGameSessionUseCase {
    constructor(private sessionStore: SessionStore) {}

    async execute(username: string, source: string) {
        return this.sessionStore.invalidate(username, source)
    }
}
