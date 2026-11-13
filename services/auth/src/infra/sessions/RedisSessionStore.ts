import { SessionStore } from '@/domain/ports/SessionStore'
import type { AppRedisClient } from '@/index'

type AuthEvent = { type: 'Authenticated'; username: string } | { type: 'Invalidated'; username: string }

export class RedisSessionStore implements SessionStore {
    private static AUTH_SET_KEY = 'game-sessions:authenticated'
    private static EVENTS_CHANNEL = 'game-sessions:events'

    constructor(private readonly redis: AppRedisClient) {}

    async create(username: string) {
        await this.redis.sAdd(RedisSessionStore.AUTH_SET_KEY, username)
        const event: AuthEvent = { type: 'Authenticated', username }
        await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
    }

    async isAuthenticated(username: string) {
        return (await this.redis.sIsMember(RedisSessionStore.AUTH_SET_KEY, username)) !== 0
    }

    async invalidate(username: string) {
        await this.redis.sRem(RedisSessionStore.AUTH_SET_KEY, username)
        const event: AuthEvent = { type: 'Invalidated', username }
        await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
    }

    async listAuthenticated() {
        const members = await this.redis.sMembers(RedisSessionStore.AUTH_SET_KEY)
        return [...members]
    }

    async clearAll() {
        const members = await this.listAuthenticated()
        await this.redis.del(RedisSessionStore.AUTH_SET_KEY)
        for (const username of members) {
            const event: AuthEvent = { type: 'Invalidated', username }
            await this.redis.publish(RedisSessionStore.EVENTS_CHANNEL, JSON.stringify(event))
        }
    }
}
