import { CaseSensitivityUsernamePolicy, UsernameCaseCheckResult } from '@/domain/policies/CaseSensitivityUsernamePolicy'
import { AuthService } from '@/domain/services/AuthService'

type LoginResult =
    | { type: 'Success'; uuid: string }
    | { type: 'InvalidCredentials' }
    | Extract<UsernameCaseCheckResult, { type: 'WrongCase' }>

export class LoginUseCase {
    constructor(private authService: AuthService, private casePolicy: CaseSensitivityUsernamePolicy) {}

    async execute(username: string, password: string): Promise<LoginResult> {
        const uuid = await this.authService.validateCredentials(username, password)

        if (uuid) {
            return { type: 'Success', uuid }
        }

        const casing = await this.casePolicy.check(username)

        if (casing.type === 'WrongCase') {
            // if the password is correct, but the username
            // is different case, we notify the user
            const retry = await this.authService.validateCredentials(casing.correctUsername, password)
            if (retry !== null) {
                return casing
            }
        }

        return { type: 'InvalidCredentials' }
    }
}
