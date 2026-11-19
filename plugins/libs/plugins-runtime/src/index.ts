import 'ses';
import './lib/modal/plugin-modal';

import { bootstrapSES } from './lib/bootstrap.js';
import {
  ɵloadPlugin,
  setContextBuilder,
  ɵloadPluginByUrl,
  ɵunloadPlugin,
} from './lib/load-plugin.js';

// Export the plugin error checker so the frontend can identify plugin errors
export { isPluginError } from './lib/create-sandbox.js';

import type { Context } from '@penpot/plugin-types';

console.log('%c[PLUGINS] Loading plugin system', 'color: #008d7c');

const sesError = bootstrapSES();

if (sesError) {
  console.error(
    '[PLUGINS] SES bootstrap failed; plugins disabled in this environment.',
    sesError,
  );
}

/** Returns the SES bootstrap error, or null when the runtime can be used. */
export const getPluginsRuntimeError = (): Error | null => sesError;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const globalThisAny$ = globalThis as any;

export const initPluginsRuntime = (contextBuilder: (id: string) => Context) => {
  if (sesError) {
    console.error('[PLUGINS] Skipping plugin runtime initialization.');
    return;
  }

  try {
    console.log('%c[PLUGINS] Initialize runtime', 'color: #008d7c');
    setContextBuilder(contextBuilder);
    globalThisAny$.ɵcontext = contextBuilder(
      '00000000-0000-0000-0000-000000000000',
    );
    globalThis.ɵloadPlugin = ɵloadPlugin;
    globalThis.ɵloadPluginByUrl = ɵloadPluginByUrl;
    globalThis.ɵunloadPlugin = ɵunloadPlugin;
  } catch (err) {
    console.error(err);
  }
};
