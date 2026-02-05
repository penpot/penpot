import type { Context } from '@penpot/plugin-types';

import { loadManifest } from './parse-manifest.js';
import { Manifest } from './models/manifest.model.js';
import { createPlugin } from './create-plugin.js';
import { ses } from './ses.js';

let plugins: Awaited<ReturnType<typeof createPlugin>>[] = [];

export type ContextBuilder = (id: string) => Context;

let contextBuilder: ContextBuilder | null = null;

export function setContextBuilder(builder: ContextBuilder) {
  contextBuilder = builder;
}

export const getPlugins = () => plugins;

const closeAllPlugins = () => {
  plugins.forEach((pluginApi) => {
    if (!(pluginApi.manifest as any).allowBackground) {
      pluginApi.plugin.close();
    }
  });

  plugins = [];
};

window.addEventListener('message', (event) => {
  try {
    for (const it of plugins) {
      it.plugin.sendMessage(event.data);
    }
  } catch (err) {
    console.error(err);
  }
});

export const loadPlugin = async function (
  manifest: Manifest,
  closeCallback?: () => void,
  apiExtensions?: Object,
) {
  try {
    const context = contextBuilder && contextBuilder(manifest.pluginId);

    if (!context) {
      return;
    }

    closeAllPlugins();

    const plugin = await createPlugin(
      ses.harden(context) as Context,
      manifest,
      () => {
        plugins = plugins.filter((api) => api !== plugin);
        closeCallback && closeCallback();
      },
      apiExtensions,
    );

    plugins.push(plugin);
  } catch (error) {
    closeAllPlugins();
    console.error(error);
  }
};

export const ɵloadPlugin = async function (
  manifest: Manifest,
  closeCallback?: () => void,
  apiExtensions?: Object,
) {
  loadPlugin(manifest, closeCallback, apiExtensions);
};

export const ɵloadPluginByUrl = async function (manifestUrl: string) {
  const manifest = await loadManifest(manifestUrl);
  ɵloadPlugin(manifest);
};

export const ɵunloadPlugin = function (id: Manifest['pluginId']) {
  const plugin = plugins.find((plugin) => plugin.manifest.pluginId === id);

  if (plugin) {
    plugin.plugin.close();
  }
};
