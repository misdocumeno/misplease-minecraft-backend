import { SessionStore } from '@/domain/ports/SessionStore'

export class InMemorySessionStore implements SessionStore {
    private sessions: Set<string> = new Set()

    create(username: string) {
        this.sessions.add(username)
        return Promise.resolve()
    }

    isAuthenticated(username: string) {
        return Promise.resolve(this.sessions.has(username))
    }

    invalidate(username: string) {
        this.sessions.delete(username)
        return Promise.resolve()
    }
}
