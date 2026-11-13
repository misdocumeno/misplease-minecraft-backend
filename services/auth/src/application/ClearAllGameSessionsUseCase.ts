import type { SessionStore } from '@/domain/ports/SessionStore'

export class ClearAllGameSessionsUseCase {
    constructor(private sessionStore: SessionStore) {}

    execute() {
        return this.sessionStore.clearAll()
    }
}
