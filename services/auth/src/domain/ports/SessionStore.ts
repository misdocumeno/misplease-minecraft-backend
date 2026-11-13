export interface SessionStore {
    create(username: string): Promise<void>
    isAuthenticated(username: string): Promise<boolean>
    invalidate(username: string): Promise<void>
    listAuthenticated(): Promise<string[]>
    clearAll(): Promise<void>
}
