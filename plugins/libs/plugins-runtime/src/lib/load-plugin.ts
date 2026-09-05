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
  apiExtensions?: object,
) {
  try {
    const context = contextBuilder && contextBuilder(manifest.pluginId);

    if (!context) {
      return;
    }

    closeAllPlugins();

    // Do NOT harden the host-created context here. `ses.harden` performs a
    // deep freeze, so hardening the context would permanently freeze
    // host-owned objects and functions reachable through it (e.g. plugin API
    // listeners and proxies the host still needs to modify on page
    // navigation). Sandbox isolation is enforced at the compartment boundary
    // instead (see `create-sandbox.ts`: hardened sandbox-owned globals and
    // `ses.safeReturn` on values crossing into the sandbox).
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
