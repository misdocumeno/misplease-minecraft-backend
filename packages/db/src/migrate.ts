import path from 'node:path'
import { fileURLToPath } from 'node:url'
import postgres from 'postgres'
import { drizzle } from 'drizzle-orm/postgres-js'
import { migrate } from 'drizzle-orm/postgres-js/migrator'

const here = path.dirname(fileURLToPath(import.meta.url))
const migrationsFolder =
    process.env.DRIZZLE_MIGRATIONS_FOLDER ?? path.join(here, '..', 'drizzle')

async function main() {
    const migrationClient = postgres(process.env.DATABASE_URL!, { max: 1 })
    const db = drizzle(migrationClient)

    console.log(`Applying migrations from ${migrationsFolder}...`)

    try {
        await migrate(db, { migrationsFolder })
        console.log('Migrations applied successfully!')
    } catch (err) {
        console.error('Error applying migrations:', err)
        process.exit(1)
    } finally {
        await migrationClient.end()
    }
}

await main()
