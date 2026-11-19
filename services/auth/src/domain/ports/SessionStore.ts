export interface SessionStore {
    /**
     * [loginId] is the caller's correlation id for the login attempt (the proxy
     * generates it and logs the same value). It is kept next to the session so a
     * backend join can log which login it belongs to.
     */
    create(username: string, loginId: string): Promise<void>
    isAuthenticated(username: string): Promise<boolean>
    loginIdFor(username: string): Promise<string | null>

    /** Returns whether a session was actually removed. */
    invalidate(username: string, source: string): Promise<boolean>
    listAuthenticated(): Promise<string[]>

    /** Returns how many sessions were removed. */
    clearAll(source: string): Promise<number>
}
