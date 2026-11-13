import type { SessionStore } from '@/domain/ports/SessionStore'

export class ListAuthenticatedSessionsUseCase {
    constructor(private sessionStore: SessionStore) {}

    execute() {
        return this.sessionStore.listAuthenticated()
    }
}
