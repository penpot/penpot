import { TokenProperty } from '@penpot/plugin-types';

/**
 * This file contains the typescript interfaces for the plugin events.
 */

// Events sent from the ui to the plugin

export interface LoadLibraryEvent {
  type: 'load-library';
}

export interface LoadTokensEvent {
  type: 'load-tokens';
  setId: string;
}

export interface AddThemeEvent {
  type: 'add-theme';
  themeGroup: string;
  themeName: string;
}

export interface AddSetEvent {
  type: 'add-set';
  setName: string;
}

export interface AddTokenEvent {
  type: 'add-token';
  setId: string;
  tokenType: string;
  tokenName: string;
  tokenValue: unknown;
}

export interface RenameThemeEvent {
  type: 'rename-theme';
  themeId: string;
  newName: string;
}

export interface RenameSetEvent {
  type: 'rename-set';
  setId: string;
  newName: string;
}

export interface RenameTokenEvent {
  type: 'rename-token';
  setId: string;
  tokenId: string;
  newName: string;
}

export interface DeleteThemeEvent {
  type: 'delete-theme';
  themeId: string;
}

export interface DeleteSetEvent {
  type: 'delete-set';
  setId: string;
}

export interface DeleteTokenEvent {
  type: 'delete-token';
  setId: string;
  tokenId: string;
}

export interface ToggleThemeEvent {
  type: 'toggle-theme';
  themeId: string;
}

export interface ToggleSetEvent {
  type: 'toggle-set';
  setId: string;
}

export interface ApplyTokenEvent {
  type: 'apply-token';
  setId: string;
  tokenId: string;
  attributes?: TokenProperty[];
}

export type PluginUIEvent =
  | LoadLibraryEvent
  | LoadTokensEvent
  | AddThemeEvent
  | AddSetEvent
  | AddTokenEvent
  | RenameThemeEvent
  | RenameSetEvent
  | RenameTokenEvent
  | DeleteThemeEvent
  | DeleteSetEvent
  | DeleteTokenEvent
  | ToggleThemeEvent
  | ToggleSetEvent
  | ApplyTokenEvent;

// Events sent from the plugin to the ui

export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export type PluginMessageEvent = ThemePluginEvent;
