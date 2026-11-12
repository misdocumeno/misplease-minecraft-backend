import { build } from 'esbuild'
import pkg from './package.json' with { type: 'json' }

await build({
    entryPoints: { migrate: 'src/migrate.ts' },
    outdir: 'dist',
    platform: 'node',
    target: 'node24',
    bundle: true,
    format: 'esm',
    sourcemap: true,
    logLevel: 'info',
    // Keep deps external; Node resolves them from the workspace install.
    external: [...Object.keys(pkg.dependencies || {})],
})
