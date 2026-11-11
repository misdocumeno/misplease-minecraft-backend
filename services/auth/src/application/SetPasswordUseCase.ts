import { z } from 'zod'
import { AuthService } from '@/domain/services/AuthService'
import { CaseSensitivityUsernamePolicy } from '@/domain/policies/CaseSensitivityUsernamePolicy'

type SetPasswordResult = { type: 'Success' } | { type: 'InvalidCredentials' } | { type: 'NotFound' }

const Schema = z.object({
    username: z.string().regex(/[a-zA-Z0-9_]{3,16}/),
    password: z.string().regex(/[!-~]{3,}/),
})

export class SetPasswordUseCase {
    constructor(
        private readonly authService: AuthService,
        private readonly casePolicy: CaseSensitivityUsernamePolicy,
    ) {}

    async execute(username: string, password: string): Promise<SetPasswordResult> {
        const parsed = Schema.safeParse({ username, password })
        if (!parsed.success) return { type: 'InvalidCredentials' }

        const casing = await this.casePolicy.check(username)
        if (casing.type === 'NotFound') return { type: 'NotFound' }

        const canonical = casing.type === 'WrongCase' ? casing.correctUsername : username
        const ok = await this.authService.setPassword(canonical, password)
        return ok ? { type: 'Success' } : { type: 'NotFound' }
    }
}
