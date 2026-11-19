import { SessionStore } from '@/domain/ports/SessionStore'

/** Test helper; production always uses RedisSessionStore. */
export class InMemorySessionStore implements SessionStore {
    private sessions: Map<string, string> = new Map()

    create(username: string, loginId: string) {
        this.sessions.set(username, loginId)
        return Promise.resolve()
    }

    isAuthenticated(username: string) {
        return Promise.resolve(this.sessions.has(username))
    }

    loginIdFor(username: string) {
        return Promise.resolve(this.sessions.get(username) ?? null)
    }

    invalidate(username: string, _source: string) {
        return Promise.resolve(this.sessions.delete(username))
    }

    listAuthenticated() {
        return Promise.resolve([...this.sessions.keys()])
    }

    clearAll(_source: string) {
        const removed = this.sessions.size
        this.sessions.clear()
        return Promise.resolve(removed)
    }
}
