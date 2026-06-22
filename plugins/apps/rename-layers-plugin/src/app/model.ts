import { Shape } from '@penpot/plugin-types';

export interface ReadyPluginEvent {
  type: 'ready';
}
export interface InitPluginEvent {
  type: 'init';
  content: {
    theme: string;
    selection: Shape[];
  };
}

export interface SelectionPluginEvent {
  type: 'selection';
  content: {
    selection: Shape[];
  };
}
export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export interface ReplaceTextPluginEvent {
  type: 'replace-text';
  content: ReplaceText;
}

export interface AddTextPluginEvent {
  type: 'add-text';
  content: AddText[];
}

export interface PreviewReplaceTextPluginEvent {
  type: 'preview-replace-text';
  content: ReplaceText;
}

export type PluginMessageEvent =
  | ReadyPluginEvent
  | InitPluginEvent
  | SelectionPluginEvent
  | ThemePluginEvent
  | ReplaceTextPluginEvent
  | AddTextPluginEvent
  | PreviewReplaceTextPluginEvent;

export interface ReplaceText {
  search: string;
  replace: string;
}

export interface AddText {
  current: string;
  new: string;
}
