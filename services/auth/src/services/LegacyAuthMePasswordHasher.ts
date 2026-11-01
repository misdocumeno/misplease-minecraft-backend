import crypto from 'crypto'
import { PasswordHasher } from '@/domain/services/PasswordHasher'

/**
 * A password hasher that replicates the AuthMe plugin
 * hashing algorithm - `sha256(sha256(password) + salt)`
 */
export class LegacyAuthMePasswordHasher implements PasswordHasher {
    /**
     * Compares the given password with the given hash.
     * @param dbHash The hash from the AuthMe database.
     * @param password The password to compare to the hash.
     * @returns Whether the password matches the hash or not.
     */
    async compare(dbHash: string, password: string) {
        const [, , salt, hash] = dbHash.split('$')
        return hash === this.sha256(this.sha256(password) + salt)
    }

    /**
     * Hashes the given password using the AuthMe method.
     * @param password The password to hash.
     * @returns The password hash.
     */
    async hash(password: string) {
        const salt = crypto.randomBytes(16).toString('hex')
        return `$SHA$${salt}$${this.sha256(this.sha256(password) + salt)}`
    }

    private sha256(text: string) {
        return crypto.createHash('sha256').update(text).digest('hex')
    }
}
