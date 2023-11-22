export type BuildStartMessage = {
  type: 'buildStart';
};

export type BuildErrorMessage = {
  type: 'buildError';
};

export type BuildDoneMessage = {
  type: 'buildDone';
};

export type ServerMessage =
  | BuildStartMessage
  | BuildErrorMessage
  | BuildDoneMessage;
