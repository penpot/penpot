import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';
import { createPluginManager } from './plugin-manager.js';
import { createSandbox, markPluginError } from './create-sandbox.js';

export async function createPlugin(
  context: Context,
  manifest: Manifest,
  onCloseCallback: () => void,
  apiExtensions?: object,
  signal?: AbortSignal,
) {
  // Closing can happen while the manager is still fetching code.
  let sandbox: ReturnType<typeof createSandbox> | undefined = undefined;

  const evaluateSandbox = async () => {
    if (plugin.destroyed || !sandbox) return;
    try {
      sandbox.evaluate();
    } catch (error) {
      markPluginError(error);
      plugin.close();
      throw error;
    }
  };

  const plugin = await createPluginManager(
    context,
    manifest,
    function onClose() {
      sandbox?.cleanGlobalThis();
      onCloseCallback();
    },
    function onReloadModal() {
      evaluateSandbox();
    },
    signal,
  );

  if (plugin.destroyed) return;

  sandbox = createSandbox(plugin, apiExtensions);

  await evaluateSandbox();

  return {
    plugin,
    manifest,
    compartment: sandbox,
    get iframeWindow() {
      return plugin.iframeWindow;
    },
  };
}
