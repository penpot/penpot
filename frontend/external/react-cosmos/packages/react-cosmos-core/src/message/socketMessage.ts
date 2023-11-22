export type MessageType = {
  type: string;
  payload?: {};
};

export type SocketMessage<T extends MessageType = MessageType> = {
  channel: 'renderer' | 'server';
  message: T;
};

export function serverSocketMessage(message: MessageType): SocketMessage {
  return { channel: 'server', message };
}

export function rendererSocketMessage(message: MessageType): SocketMessage {
  return { channel: 'renderer', message };
}
