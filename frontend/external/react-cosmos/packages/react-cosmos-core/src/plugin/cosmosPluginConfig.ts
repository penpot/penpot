// TODO: Validate config schema on config import
// Maybe: Allow `server` and `ui` values to be [true] for default paths?
export type RawCosmosPluginConfig = {
  name: string;
  server?: string;
  ui?: string;
};

export type CosmosPluginConfig = {
  name: string;
  rootDir: string;
  server?: string;
  ui?: string;
};

export type UiCosmosPluginConfig = CosmosPluginConfig & {
  ui: string;
};
