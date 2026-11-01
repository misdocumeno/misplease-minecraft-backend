import postgres from 'postgres'
import { drizzle } from 'drizzle-orm/postgres-js'
import { migrate } from 'drizzle-orm/postgres-js/migrator'
;(async () => {
    const migrationClient = postgres(process.env.DATABASE_URL!, { max: 1 })
    const db = drizzle(migrationClient)

    console.log('Applying migrations...')

    try {
        await migrate(db, { migrationsFolder: './drizzle' })
        console.log('Migrations applied successfully!')
    } catch (err) {
        console.error('Error applying migrations:', err)
        process.exit(1)
    } finally {
        await migrationClient.end()
    }
})()
