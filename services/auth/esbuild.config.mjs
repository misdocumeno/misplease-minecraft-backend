import { build } from 'esbuild'
import pkg from './package.json' with { type: 'json' }

const external = Object.keys(pkg.dependencies || {}).filter((name) => name !== '@misplease/db')

await build({
    entryPoints: { index: 'src/index.ts' },
    outdir: 'dist',

    platform: 'node',
    target: 'node24',

    bundle: true,
    format: 'cjs',

    sourcemap: true,

    logLevel: 'info',

    external,
})
