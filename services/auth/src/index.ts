import express from 'express'
import { createClient } from 'redis'
import postgres from 'postgres'
import { drizzle } from 'drizzle-orm/postgres-js'
import { players } from './infra/database/schema/players'
import { playerLastServer } from './infra/database/schema/lastServer'
import { DrizzlePlayerRepository } from './infra/database/DrizzlePlayerRepository'
import { GameSessionController } from './infra/http/GameSessionController'
import { CreateGameSessionUseCase } from './application/CreateGameSessionUseCase'
import { AuthService } from './domain/services/AuthService'
import { LegacyAuthMePasswordHasher } from './services/LegacyAuthMePasswordHasher'
import { IsGameSessionAuthenticatedUseCase } from './application/IsGameSessionAuthenticatedUseCase'
import { InvalidateGameSessionUseCase } from './application/InvalidateGameSessionUseCase'
import { AuthController } from './infra/http/AuthController'
import { LoginUseCase } from './application/LoginUseCase'
import { RegisterUseCase } from './application/RegisterUseCase'
import { SetPasswordUseCase } from './application/SetPasswordUseCase'
import { GetPlayerUseCase } from './application/GetPlayerUseCase'
import { ListOnlinePlayersUseCase } from './application/ListOnlinePlayersUseCase'
import { CaseSensitivityUsernamePolicy } from './domain/policies/CaseSensitivityUsernamePolicy'
import { UsernameCasingController } from './infra/http/UsernameCasingController'
import { AdminController } from './infra/http/AdminController'
import { RedisSessionStore } from './infra/sessions/RedisSessionStore'
import { BukkitOfflineUUIDGenerator } from './services/BukkitOfflineUUIDGenerator'

const redis = createClient({ url: process.env.REDIS_URL! })

export type AppRedisClient = typeof redis

async function main() {
    await redis.connect()

    const conn = postgres(process.env.DATABASE_URL!)
    const db = drizzle(conn, { schema: { players, playerLastServer } })
    const playerRepository = new DrizzlePlayerRepository(db)

    const app = express()
    app.use(express.json())

    const passwordHasher = new LegacyAuthMePasswordHasher()
    const uuidGenerator = new BukkitOfflineUUIDGenerator()
    const authService = new AuthService(playerRepository, passwordHasher, uuidGenerator)

    const sessionRouter = express.Router()

    const sessionStore = new RedisSessionStore(redis)
    const createSessionUseCase = new CreateGameSessionUseCase(authService, sessionStore)
    const isAuthenticatedUseCase = new IsGameSessionAuthenticatedUseCase(sessionStore)
    const invalidateSessionUseCase = new InvalidateGameSessionUseCase(sessionStore)
    const sessionController = new GameSessionController(
        createSessionUseCase,
        isAuthenticatedUseCase,
        invalidateSessionUseCase,
    )

    sessionController.registerRoutes(sessionRouter)
    app.use('/session', sessionRouter)

    const authRouter = express.Router()

    const casePolicy = new CaseSensitivityUsernamePolicy(playerRepository)
    const loginUseCase = new LoginUseCase(authService, casePolicy)
    const registerUseCase = new RegisterUseCase(authService, casePolicy)
    const authController = new AuthController(loginUseCase, registerUseCase)

    authController.registerRoutes(authRouter)
    app.use('/auth', authRouter)

    const casingRouter = express.Router()
    const casingController = new UsernameCasingController(casePolicy)
    casingController.registerRoutes(casingRouter)
    app.use('/casing', casingRouter)

    const setPasswordUseCase = new SetPasswordUseCase(authService, casePolicy)
    const getPlayerUseCase = new GetPlayerUseCase(playerRepository, sessionStore, redis, db)
    const listOnlineUseCase = new ListOnlinePlayersUseCase(redis, sessionStore)
    const adminController = new AdminController(
        registerUseCase,
        setPasswordUseCase,
        getPlayerUseCase,
        listOnlineUseCase,
    )
    const adminRouter = express.Router()
    adminController.registerRoutes(adminRouter)
    app.use('/admin', adminRouter)

    app.listen(3000, (err) => {
        if (err) throw err
        console.log('Listening on port 3000')
    })
}

main().catch(console.error)
