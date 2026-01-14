import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';
import { createPluginManager } from './plugin-manager.js';
import { createSandbox } from './create-sandbox.js';

export async function createPlugin(
  context: Context,
  manifest: Manifest,
  onCloseCallback: () => void,
) {
  const evaluateSandbox = async () => {
    try {
      sandbox.evaluate();
    } catch (error) {
      console.error(error);

      plugin.close();
    }
  };

  const plugin = await createPluginManager(
    context,
    manifest,
    function onClose() {
      sandbox.cleanGlobalThis();
      onCloseCallback();
    },
    function onReloadModal() {
      evaluateSandbox();
    },
  );

  const sandbox = createSandbox(plugin);

  evaluateSandbox();

  return {
    plugin,
    manifest,
    compartment: sandbox,
  };
}
