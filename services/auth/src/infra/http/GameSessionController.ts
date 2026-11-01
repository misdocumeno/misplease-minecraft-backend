import { z } from 'zod'
import type { Router, Request, Response } from 'express'
import { CreateGameSessionUseCase } from '@/application/CreateGameSessionUseCase'
import { IsGameSessionAuthenticatedUseCase } from '@/application/IsGameSessionAuthenticatedUseCase'
import { InvalidateGameSessionUseCase } from '@/application/InvalidateGameSessionUseCase'

const LoginBodySchema = z.object({
    username: z.string(),
    password: z.string(),
})

export class GameSessionController {
    constructor(
        private readonly sessionCreateUseCase: CreateGameSessionUseCase,
        private readonly authenticatedUseCase: IsGameSessionAuthenticatedUseCase,
        private readonly sessionInvalidateUseCase: InvalidateGameSessionUseCase,
    ) {}

    registerRoutes(router: Router) {
        router.post('/login', this.login.bind(this))
        router.post('/invalidate', this.invalidate.bind(this))
        router.get('/is-authenticated', this.isAuthenticated.bind(this))
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
        this.sessionInvalidateUseCase.execute(username)
        res.status(200).json({ type: 'Success' })
    }

    private async isAuthenticated(req: Request, res: Response) {
        const { username } = z.object({ username: z.string() }).parse(req.query)
        const authenticated = await this.authenticatedUseCase.execute(username)
        res.json({ authenticated })
    }
}
