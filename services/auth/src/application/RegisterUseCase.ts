import { z } from 'zod'
import { AuthService } from '@/domain/services/AuthService'
import { CaseSensitivityUsernamePolicy } from '@/domain/policies/CaseSensitivityUsernamePolicy'

type RegisterResult =
    | { type: 'Success'; uuid: string }
    | { type: 'InvalidCredentials' }
    | { type: 'UsernameAlreadyTaken' }

const RegisterSchema = z.object({
    username: z.string().regex(/[a-zA-Z0-9_]{3,16}/),
    password: z.string().regex(/[!-~]{3,}/),
})

export class RegisterUseCase {
    constructor(
        private readonly authService: AuthService,
        private readonly casePolicy: CaseSensitivityUsernamePolicy,
    ) {}

    async execute(username: string, password: string): Promise<RegisterResult> {
        const parsed = RegisterSchema.safeParse({ username, password })

        if (!parsed.success) {
            return { type: 'InvalidCredentials' }
        }

        const casing = await this.casePolicy.check(username)

        if (casing.type !== 'NotFound') {
            return { type: 'UsernameAlreadyTaken' }
        }

        const uuid = await this.authService.register(username, password)
        return { type: 'Success', uuid }
    }
}
