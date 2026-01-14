import type { Manifest } from './lib/models/manifest.model';

export declare global {
  declare namespace globalThis {
    function ɵloadPlugin(cofig: Manifest): Promise<void>;
    function ɵloadPluginByUrl(url: string): Promise<void>;
    function ɵunloadPlugin(id: Manifest['pluginId']): void;
  }
}
