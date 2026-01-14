export type GenerationTypes =
  | 'paragraphs'
  | 'sentences'
  | 'words'
  | 'characters';

export interface InitPluginUIEvent {
  type: 'ready';
}

export interface TextPluginUIEvent {
  type: 'text';
  generationType: GenerationTypes;
  startWithLorem: boolean;
  size: number;
  autoClose: boolean;
}
export type PluginUIEvent = InitPluginUIEvent | TextPluginUIEvent;

export interface InitPluginEvent {
  type: 'init';
  content: {
    theme: string;
    selection: number;
  };
}
export interface SelectionPluginEvent {
  type: 'selection';
  content: number;
}

export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export type PluginMessageEvent =
  | InitPluginEvent
  | SelectionPluginEvent
  | ThemePluginEvent;
