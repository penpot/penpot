#!/usr/bin/env node
/**
 * Emits dist/index.js and dist/index.d.ts that re-export from the plugin
 * via relative paths. We do not run tsc so we avoid pulling the plugin into
 * our program (rootDir / Figma globals would fail). Consumers and their
 * bundlers resolve these paths to the plugin package.
 */
import { mkdir, writeFile } from 'fs/promises';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname);
const distDir = join(root, 'dist');
const prefix = '../penpot-exporter-figma-plugin';

const indexJs = `/** Re-exports from penpot-exporter-figma-plugin - do not edit by hand. Consumed by bundlers (e.g. Vite) which resolve these paths to plugin source. */
export * from '${prefix}/ui-src/types/index';
export {
  transformSceneNode,
  SUPPORTED_SCENE_NODE_TYPES,
} from '${prefix}/plugin-src/transformers/transformSceneNode';
export { transformId } from '${prefix}/plugin-src/transformers/partials/transformIds';
export { clearAllState } from '${prefix}/plugin-src/libraries';
`;

const indexDts = `/** Re-exports from penpot-exporter-figma-plugin - do not edit by hand. */
export * from '${prefix}/ui-src/types/index';
export {
  transformSceneNode,
  SUPPORTED_SCENE_NODE_TYPES,
} from '${prefix}/plugin-src/transformers/transformSceneNode';
export type { TransformOptions } from '${prefix}/plugin-src/transformOptions';
export { transformId } from '${prefix}/plugin-src/transformers/partials/transformIds';
export { clearAllState } from '${prefix}/plugin-src/libraries';
`;

await mkdir(distDir, { recursive: true });
await writeFile(join(distDir, 'index.js'), indexJs, 'utf8');
await writeFile(join(distDir, 'index.d.ts'), indexDts, 'utf8');
console.log('Wrote dist/index.js and dist/index.d.ts');