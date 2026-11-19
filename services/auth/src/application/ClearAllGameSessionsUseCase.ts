import type { SessionStore } from '@/domain/ports/SessionStore'

export class ClearAllGameSessionsUseCase {
    constructor(private sessionStore: SessionStore) {}

    execute(source: string) {
        return this.sessionStore.clearAll(source)
    }
}
