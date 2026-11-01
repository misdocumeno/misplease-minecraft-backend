export interface UUIDGenerator {
    fromUsername(username: string): Promise<string>
}
