import { SessionStore } from '@/domain/ports/SessionStore'
import type { AppRedisClient } from '@/index'
import { logEvent } from '@/infra/log/logger'

type AuthEvent =
    | { type: 'Authenticated'; username: string; loginId: string }
    | { type: 'Invalidated'; username: string; source: string }

export class RedisSessionStore implements SessionStore {
    private static AUTH_SET_KEY = 'game-sessions:authenticated'
    private static EVENTS_CHANNEL = 'game-sessions:events'
    private static ORIGIN_PREFIX = 'game-sessions:origin:'
    /** A session lives in memory of the player, not here; the id is only for logs. */
    private static ORIGIN_TTL_SECONDS = 24 * 60 * 60

    constructor(private readonly redis: AppRedisClient) {}

    async create(username: string, loginId: string) {
        await this.redis.sAdd(RedisSessionStore.AUTH_SET_KEY, username)
        await this.redis.set(RedisSessionStore.originKey(username), loginId, {
            EX: RedisSessionStore.ORIGIN_TTL_SECONDS,
        })
        logEvent('info', 'session.create', { username, loginId })
        const event: AuthEvent = { type: 'Authenticated', username, loginId }
        await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
    }

    async isAuthenticated(username: string) {
        return (await this.redis.sIsMember(RedisSessionStore.AUTH_SET_KEY, username)) !== 0
    }

    async loginIdFor(username: string) {
        return await this.redis.get(RedisSessionStore.originKey(username))
    }

    async invalidate(username: string, source: string) {
        // Only announce a leave for someone who was actually authenticated. The proxy
        // drops a stale session on every LoginEvent; publishing unconditionally made a
        // first-time join emit "left the game" before the player had even /login'd.
        const loginId = await this.loginIdFor(username)
        const removed = await this.redis.sRem(RedisSessionStore.AUTH_SET_KEY, username)
        await this.redis.del(RedisSessionStore.originKey(username))
        logEvent('info', 'session.invalidate', {
            username,
            source,
            loginId,
            removed: removed > 0,
        })
        if (removed === 0) return false
        const event: AuthEvent = { type: 'Invalidated', username, source }
        await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
        return true
    }

    async listAuthenticated() {
        const members = await this.redis.sMembers(RedisSessionStore.AUTH_SET_KEY)
        return [...members]
    }

    async clearAll(source: string) {
        const members = await this.listAuthenticated()
        if (members.length === 0) {
            logEvent('info', 'session.clear-all', { source, removed: 0 })
            return 0
        }
        await this.redis.del(RedisSessionStore.AUTH_SET_KEY)
        for (const username of members) {
            await this.redis.del(RedisSessionStore.originKey(username))
            const event: AuthEvent = { type: 'Invalidated', username, source }
            await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
        }
        logEvent('info', 'session.clear-all', { source, removed: members.length })
        return members.length
    }

    private static originKey(username: string): string {
        return `${RedisSessionStore.ORIGIN_PREFIX}${username}`
    }
}
