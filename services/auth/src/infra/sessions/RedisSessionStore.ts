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
    private static CREATED_PREFIX = 'game-sessions:created-at:'
    /**
     * TTL of the correlation metadata only. The session itself is membership in
     * AUTH_SET_KEY and never expires on its own: it is removed by DisconnectEvent,
     * the proxy reconcile, or clear-all. A player is never asked to /login again
     * for being connected too long, so this must outlive any plausible session;
     * it is just a sweeper for rows whose invalidation never arrived.
     */
    private static SESSION_META_TTL_SECONDS = 7 * 24 * 60 * 60

    constructor(private readonly redis: AppRedisClient) {}

    async create(username: string, loginId: string) {
        await this.redis.sAdd(RedisSessionStore.AUTH_SET_KEY, username)
        await this.redis.set(RedisSessionStore.originKey(username), loginId, {
            EX: RedisSessionStore.SESSION_META_TTL_SECONDS,
        })
        // Lets the proxy reconciler tell "just logged in, still handing off to a
        // backend" apart from a genuinely orphaned session. See auth-sessions.md.
        await this.redis.set(
            RedisSessionStore.createdKey(username),
            String(Math.floor(Date.now() / 1000)),
            { EX: RedisSessionStore.SESSION_META_TTL_SECONDS },
        )
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
        await this.redis.del([
            RedisSessionStore.originKey(username),
            RedisSessionStore.createdKey(username),
        ])
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
            await this.redis.del([
                RedisSessionStore.originKey(username),
                RedisSessionStore.createdKey(username),
            ])
            const event: AuthEvent = { type: 'Invalidated', username, source }
            await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
        }
        logEvent('info', 'session.clear-all', { source, removed: members.length })
        return members.length
    }

    private static originKey(username: string): string {
        return `${RedisSessionStore.ORIGIN_PREFIX}${username}`
    }

    private static createdKey(username: string): string {
        return `${RedisSessionStore.CREATED_PREFIX}${username}`
    }
}
