import esbuild from 'esbuild';
import { existsSync } from 'fs';
import { readdir } from 'fs/promises';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, '../..');
const appsDir = resolve(rootDir, 'apps');

const watch = process.argv.includes('--watch');
const filterPlugin = process.argv
    .find((arg) => arg.startsWith('--plugin='))
    ?.replace('--plugin=', '');

async function getPluginEntryPoints() {
    const entries = await readdir(appsDir, { withFileTypes: true });
    const entryPoints = [];

    for (const entry of entries) {
        if (!entry.isDirectory()) continue;

        if (filterPlugin && entry.name !== filterPlugin) continue;

        const pluginTs = resolve(appsDir, entry.name, 'src/plugin.ts');
        const tsconfigPlugin = resolve(
            appsDir,
            entry.name,
            'tsconfig.plugin.json',
        );

        if (existsSync(pluginTs) && existsSync(tsconfigPlugin)) {
            entryPoints.push({
                name: entry.name,
                entryPoint: pluginTs,
                tsconfig: tsconfigPlugin,
                outdir: resolve(appsDir, entry.name, 'src/assets'),
            });
        }
    }

    return entryPoints;
}

async function buildPlugin(plugin) {
    const options = {
        entryPoints: [plugin.entryPoint],
        bundle: true,
        outfile: resolve(plugin.outdir, 'plugin.js'),
        minify: !watch,
        format: 'esm',
        tsconfig: plugin.tsconfig,
        logLevel: 'info',
    };

    if (watch) {
        const ctx = await esbuild.context(options);
        await ctx.watch();
        console.log(`[buildPlugin] Watching ${plugin.name}...`);
        return ctx;
    } else {
        await esbuild.build(options);
        console.log(`[buildPlugin] Built ${plugin.name}`);
    }
}

async function main() {
    const plugins = await getPluginEntryPoints();

    if (plugins.length === 0) {
        console.warn('[buildPlugin] No plugins found to build.');
        return;
    }

    console.log(
        `[buildPlugin] ${watch ? 'Watching' : 'Building'} ${plugins.length} plugin(s): ${plugins.map((p) => p.name).join(', ')}`,
    );

    const results = await Promise.all(plugins.map(buildPlugin));

    if (watch) {
        process.on('SIGINT', async () => {
            await Promise.all(results.map((ctx) => ctx?.dispose()));
            process.exit(0);
        });
    }
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
