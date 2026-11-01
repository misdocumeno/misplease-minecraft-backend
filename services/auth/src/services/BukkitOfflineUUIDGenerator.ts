import { UUIDGenerator } from '@/domain/services/UUIDGenerator'
import crypto from 'crypto'

/**
 * A UUID generator that replicates the Bukkit offline UUID generation.
 */
export class BukkitOfflineUUIDGenerator implements UUIDGenerator {
    /**
     * Generates a UUID based on the username, just like spigot does on offline servers.
     * The method is basically md5(`OfflinePlayer:${username}`) and some more stuff.
     * @see https://github.com/openjdk-mirror/jdk7u-jdk/blob/master/src/share/classes/java/util/UUID.java#L163
     * @param username The username to generate the UUID for
     * @returns The UUID for the given username
     */
    async fromUsername(username: string) {
        const buffer = crypto.createHash('md5').update(`OfflinePlayer:${username}`).digest()
        buffer[6] = (buffer[6]! & 0x0f) | 0x30
        buffer[8] = (buffer[8]! & 0x3f) | 0x80
        const hash = buffer.toString('hex')
        const uuid = [
            hash.slice(0, 8),
            hash.slice(8, 12),
            hash.slice(12, 16),
            hash.slice(16, 20),
            hash.slice(20, 32),
        ].join('-')
        return Promise.resolve(uuid)
    }
}
