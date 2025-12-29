export interface InitPluginEvent {
  type: 'init';
  content: {
    theme: string;
  };
}

export interface InsertIconEvent {
  type: 'insert-icon';
  content: {
    svg: string;
    name: string;
  };
}

export interface InitPluginUIEvent {
  type: 'ready';
}

export type PluginUIEvent = InitPluginUIEvent | InsertIconEvent;

export interface ThemePluginEvent {
  type: 'theme';
  content: string;
}

export type PluginMessageEvent = InitPluginEvent | ThemePluginEvent;
