import { z } from 'zod'
import type { Router, Request, Response } from 'express'
import { LoginUseCase } from '@/application/LoginUseCase'
import { RegisterUseCase } from '@/application/RegisterUseCase'

const CredentialsSchema = z.object({
    username: z.string(),
    password: z.string(),
})

export class AuthController {
    constructor(private readonly loginUseCase: LoginUseCase, private readonly registerUseCase: RegisterUseCase) {}

    registerRoutes(router: Router) {
        router.post('/login', this.login.bind(this))
        router.post('/register', this.register.bind(this))
    }

    private async login(req: Request, res: Response) {
        const { username, password } = CredentialsSchema.parse(req.body)
        const result = await this.loginUseCase.execute(username, password)
        return result.type === 'Success'
            ? res.json({ ok: true, code: result.type })
            : res.status(401).json({ ok: false, code: result.type })
    }

    private async register(req: Request, res: Response) {
        const { username, password } = CredentialsSchema.parse(req.body)
        const result = await this.registerUseCase.execute(username, password)
        switch (result.type) {
            case 'UsernameAlreadyTaken':
                return res.status(409).json({ ok: false, code: result.type })
            case 'InvalidCredentials':
                return res.status(422).json({ ok: false, code: result.type })
            case 'Success':
                return res.json({ ok: true, code: result.type })
        }
    }
}
