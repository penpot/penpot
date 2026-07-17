import type {
  CoverageReport,
  RunSummary,
  TestMeta,
  TestResult,
} from './framework/types';

// Messages sent from the UI iframe to the plugin sandbox.
export interface ReadyMessage {
  type: 'ready';
}

export interface RunMessage {
  type: 'run';
  ids: string[] | 'all';
}

/** Requests that an in-progress run stop after the current test finishes. */
export interface StopMessage {
  type: 'stop';
}

/** Carries the freshly built tests bundle source to be evaluated in the sandbox. */
export interface ReloadTestsMessage {
  type: 'reloadTests';
  code: string;
}

export type UIToPluginMessage =
  ReadyMessage | RunMessage | StopMessage | ReloadTestsMessage;

// Messages sent from the plugin sandbox to the UI iframe.
export interface TestsMessage {
  type: 'tests';
  tests: TestMeta[];
}

export interface ResultMessage {
  type: 'result';
  result: TestResult;
}

export interface RunCompleteMessage {
  type: 'runComplete';
  summary: RunSummary;
  coverage: CoverageReport;
  /** True when the run ended early because the user pressed Stop. */
  stopped?: boolean;
}

export interface ThemeMessage {
  type: 'theme';
  theme: string;
}

/** Sent after a reload attempt so the UI can surface success/failure. */
export interface ReloadedMessage {
  type: 'reloaded';
  ok: boolean;
  error?: string;
}

export type PluginToUIMessage =
  | TestsMessage
  | ResultMessage
  | RunCompleteMessage
  | ThemeMessage
  | ReloadedMessage;
