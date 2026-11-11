import { build } from 'esbuild'
import pkg from './package.json' with { type: 'json' }

await build({
    entryPoints: { migrate: 'src/migrate.ts' },
    outdir: 'dist',
    platform: 'node',
    target: 'node24',
    bundle: true,
    format: 'cjs',
    sourcemap: true,
    logLevel: 'info',
    external: [...Object.keys(pkg.dependencies || {})],
})
