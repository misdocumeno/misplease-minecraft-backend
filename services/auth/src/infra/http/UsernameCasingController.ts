import { z } from 'zod'
import type { Router, Request, Response } from 'express'
import { CaseSensitivityUsernamePolicy } from '@/domain/policies/CaseSensitivityUsernamePolicy'

export class UsernameCasingController {
    constructor(private readonly casePolicy: CaseSensitivityUsernamePolicy) {}

    registerRoutes(router: Router) {
        router.get('/check', this.check.bind(this))
    }

    private async check(req: Request, res: Response) {
        const { username } = z.object({ username: z.string() }).parse(req.query)
        res.json(await this.casePolicy.check(username))
    }
}
