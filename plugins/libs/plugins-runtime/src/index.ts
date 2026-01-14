import 'ses';
import './lib/modal/plugin-modal';

import {
  ɵloadPlugin,
  setContextBuilder,
  ɵloadPluginByUrl,
  ɵunloadPlugin,
} from './lib/load-plugin.js';

import type { Context } from '@penpot/plugin-types';

console.log('%c[PLUGINS] Loading plugin system', 'color: #008d7c');

repairIntrinsics({
  evalTaming: 'unsafeEval',
  stackFiltering: 'verbose',
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  errorTrapping: 'none',
  unhandledRejectionTrapping: 'none',
});

const globalThisAny$ = globalThis as any;

export const initPluginsRuntime = (contextBuilder: (id: string) => Context) => {
  try {
    console.log('%c[PLUGINS] Initialize runtime', 'color: #008d7c');
    setContextBuilder(contextBuilder);
    globalThisAny$.ɵcontext = contextBuilder('TEST');
    globalThis.ɵloadPlugin = ɵloadPlugin;
    globalThis.ɵloadPluginByUrl = ɵloadPluginByUrl;
    globalThis.ɵunloadPlugin = ɵunloadPlugin;
  } catch (err) {
    console.error(err);
  }
};
