import { LibraryColor } from '@penpot/plugin-types';

export interface Token {
  $value: string;
  $type: string;
}

export interface TokenFileExtraData {
  $themes: [];
  $metadata: TokenFileMetada;
}

export interface TokenFileMetada {
  activeThemes: [];
  tokenSetOrder: [];
  activeSets: [];
}

export type TokenStructure = {
  [key: string]: Token | TokenStructure;
};

export interface GETColorsPluginUIEvent {
  type: 'get-colors';
}

export interface ResetPluginUIEvent {
  type: 'reset';
}

export interface ResizePluginUIEvent {
  type: 'resize';
  height: number;
  width: number;
}

export type PluginUIEvent =
  | GETColorsPluginUIEvent
  | ResizePluginUIEvent
  | ResetPluginUIEvent;

export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export interface SetColorsPluginEvent {
  type: 'set-colors';
  colors: LibraryColor[] | null;
  fileName: string;
}

export type PluginMessageEvent = ThemePluginEvent | SetColorsPluginEvent;
