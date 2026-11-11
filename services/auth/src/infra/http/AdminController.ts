import { z } from 'zod'
import type { Router, Request, Response } from 'express'
import { RegisterUseCase } from '@/application/RegisterUseCase'
import { SetPasswordUseCase } from '@/application/SetPasswordUseCase'
import { GetPlayerUseCase } from '@/application/GetPlayerUseCase'
import { ListOnlinePlayersUseCase } from '@/application/ListOnlinePlayersUseCase'
import { requireAdminToken } from './adminAuth'

const CredentialsSchema = z.object({
    username: z.string(),
    password: z.string(),
})

export class AdminController {
    constructor(
        private readonly registerUseCase: RegisterUseCase,
        private readonly setPasswordUseCase: SetPasswordUseCase,
        private readonly getPlayerUseCase: GetPlayerUseCase,
        private readonly listOnlineUseCase: ListOnlinePlayersUseCase,
    ) {}

    registerRoutes(router: Router) {
        router.use(requireAdminToken)
        router.post('/register', this.register.bind(this))
        router.post('/set-password', this.setPassword.bind(this))
        router.get('/players/:username', this.get.bind(this))
        router.get('/online', this.online.bind(this))
    }

    private async register(req: Request, res: Response) {
        const parsed = CredentialsSchema.safeParse(req.body)
        if (!parsed.success) {
            return res.status(422).json({ ok: false, code: 'InvalidCredentials' })
        }
        const result = await this.registerUseCase.execute(parsed.data.username, parsed.data.password)
        switch (result.type) {
            case 'UsernameAlreadyTaken':
                return res.status(409).json({ ok: false, code: result.type })
            case 'InvalidCredentials':
                return res.status(422).json({ ok: false, code: result.type })
            case 'Success':
                return res.json({ ok: true, code: result.type, uuid: result.uuid })
        }
    }

    private async setPassword(req: Request, res: Response) {
        const parsed = CredentialsSchema.safeParse(req.body)
        if (!parsed.success) {
            return res.status(422).json({ ok: false, code: 'InvalidCredentials' })
        }
        const result = await this.setPasswordUseCase.execute(parsed.data.username, parsed.data.password)
        switch (result.type) {
            case 'NotFound':
                return res.status(404).json({ ok: false, code: result.type })
            case 'InvalidCredentials':
                return res.status(422).json({ ok: false, code: result.type })
            case 'Success':
                return res.json({ ok: true, code: result.type })
        }
    }

    private async get(req: Request, res: Response) {
        const username = req.params.username
        if (typeof username !== 'string' || !username) {
            return res.status(422).json({ ok: false, code: 'InvalidCredentials' })
        }
        const result = await this.getPlayerUseCase.execute(username)
        if (result.type === 'NotFound') {
            return res.status(404).json({ ok: false, code: result.type })
        }
        const { type: _type, ...player } = result
        return res.json({ ok: true, code: 'Success', player })
    }

    private async online(_req: Request, res: Response) {
        const players = await this.listOnlineUseCase.execute()
        return res.json({ ok: true, code: 'Success', players })
    }
}
