import { Shape } from '@penpot/plugin-types';

export interface InitPluginUIEvent {
  type: 'ready';
}

export type PluginUIEvent = InitPluginUIEvent;

export interface InitPluginEvent {
  type: 'init';
  content: {
    theme: string;
    selection: Shape[];
  };
}
export interface SelectionPluginEvent {
  type: 'selection';
  content: Shape[];
}

export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export type PluginMessageEvent =
  | InitPluginEvent
  | SelectionPluginEvent
  | ThemePluginEvent;
