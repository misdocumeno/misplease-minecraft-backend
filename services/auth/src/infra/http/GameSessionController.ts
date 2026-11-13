import { z } from 'zod'
import type { Router, Request, Response } from 'express'
import { CreateGameSessionUseCase } from '@/application/CreateGameSessionUseCase'
import { IsGameSessionAuthenticatedUseCase } from '@/application/IsGameSessionAuthenticatedUseCase'
import { InvalidateGameSessionUseCase } from '@/application/InvalidateGameSessionUseCase'
import { ClearAllGameSessionsUseCase } from '@/application/ClearAllGameSessionsUseCase'
import { ListAuthenticatedSessionsUseCase } from '@/application/ListAuthenticatedSessionsUseCase'

const LoginBodySchema = z.object({
    username: z.string(),
    password: z.string(),
})

export class GameSessionController {
    constructor(
        private readonly sessionCreateUseCase: CreateGameSessionUseCase,
        private readonly authenticatedUseCase: IsGameSessionAuthenticatedUseCase,
        private readonly sessionInvalidateUseCase: InvalidateGameSessionUseCase,
        private readonly clearAllUseCase: ClearAllGameSessionsUseCase,
        private readonly listAuthenticatedUseCase: ListAuthenticatedSessionsUseCase,
    ) {}

    registerRoutes(router: Router) {
        router.post('/login', this.login.bind(this))
        router.post('/invalidate', this.invalidate.bind(this))
        router.post('/clear-all', this.clearAll.bind(this))
        router.get('/is-authenticated', this.isAuthenticated.bind(this))
        router.get('/authenticated', this.listAuthenticated.bind(this))
    }

    private async login(req: Request, res: Response) {
        const { username, password } = LoginBodySchema.parse(req.body)
        const ok = await this.sessionCreateUseCase.execute(username, password)
        if (ok) {
            res.status(200).json({ type: 'Success' })
        } else {
            res.status(401).json({ type: 'InvalidCredentials' })
        }
    }

    private async invalidate(req: Request, res: Response) {
        const { username } = z.object({ username: z.string() }).parse(req.body)
        // Must await: otherwise reconnect can race and still see the Redis session.
        await this.sessionInvalidateUseCase.execute(username)
        res.status(200).json({ type: 'Success' })
    }

    private async clearAll(_req: Request, res: Response) {
        await this.clearAllUseCase.execute()
        res.status(200).json({ type: 'Success' })
    }

    private async isAuthenticated(req: Request, res: Response) {
        const { username } = z.object({ username: z.string() }).parse(req.query)
        const authenticated = await this.authenticatedUseCase.execute(username)
        res.json({ authenticated })
    }

    private async listAuthenticated(_req: Request, res: Response) {
        const usernames = await this.listAuthenticatedUseCase.execute()
        res.json({ usernames })
    }
}
