import type { Context } from '@penpot/plugin-types';

import { loadManifest } from './parse-manifest.js';
import { Manifest } from './models/manifest.model.js';
import { createPlugin } from './create-plugin.js';

let plugins: Awaited<ReturnType<typeof createPlugin>>[] = [];

export type ContextBuilder = (id: string) => Context;

let contextBuilder: ContextBuilder | null = null;

export function setContextBuilder(builder: ContextBuilder) {
  contextBuilder = builder;
}

export const getPlugins = () => plugins;

const closeAllPlugins = () => {
  plugins.forEach((pluginApi) => {
    /* eslint-disable  @typescript-eslint/no-explicit-any */
    if (!(pluginApi.manifest as any)?.allowBackground) {
      pluginApi.plugin.close();
    }
  });

  plugins = [];
};

window.addEventListener('message', (event) => {
  try {
    const senderPlugin = plugins.find((it) => it.iframeWindow === event.source);

    if (senderPlugin) {
      senderPlugin.plugin.sendMessage(event.data);
    }
  } catch (err) {
    console.error(err);
  }
});

export const loadPlugin = async function (
  manifest: Manifest,
  closeCallback?: () => void,
  apiExtensions?: object,
) {
  try {
    const context = contextBuilder && contextBuilder(manifest.pluginId);

    if (!context) {
      return;
    }

    closeAllPlugins();

    // The host context is not deeply frozen at this load stage.
    //
    // The context still contains host-internal function objects and shared
    // prototypes that the host may legitimately extend after plugin load
    // (for example, by assigning custom properties). Deep-freezing here
    // would freeze those prototypes before SES override taming completes,
    // preventing later host-side mutations with a "Cannot assign to read
    // only property" TypeError.
    //
    // Responsibility boundary: this function forwards the context to the
    // sandbox layer without deep-freezing it. The public API that plugins
    // consume is constructed by the API module (`api/index.ts`), and
    // `createSandbox`'s proxy handler applies `ses.safeReturn` to values
    // crossing into the sandbox. Compartment isolation and intrinsics
    // hardening are performed by createSandbox, not here.
    const plugin = await createPlugin(
      context,
      manifest,
      () => {
        plugins = plugins.filter((api) => api !== plugin);

        if (closeCallback) {
          closeCallback();
        }
      },
      apiExtensions,
    );
    plugins.push(plugin);
  } catch (error) {
    closeAllPlugins();
    throw error;
  }
};

export const ɵloadPlugin = async function (
  manifest: Manifest,
  closeCallback?: () => void,
  apiExtensions?: object,
) {
  await loadPlugin(manifest, closeCallback, apiExtensions);
};

export const ɵloadPluginByUrl = async function (manifestUrl: string) {
  const manifest = await loadManifest(manifestUrl);
  await ɵloadPlugin(manifest);
};

export const ɵunloadPlugin = function (id: Manifest['pluginId']) {
  const plugin = plugins.find((plugin) => plugin.manifest.pluginId === id);

  if (plugin) {
    plugin.plugin.close();
  }
};
