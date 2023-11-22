import { setTimeout } from 'node:timers/promises';
import {
  RendererConnect,
  RendererRequest,
  RendererResponse,
} from 'react-cosmos-core';
import { act } from 'react-test-renderer';

type Args = {
  onRendererResponse: (msg: RendererResponse) => unknown;
};
export function createTestRendererConnect({ onRendererResponse }: Args) {
  let messageHandlers: ((msg: RendererRequest) => unknown)[] = [];

  const rendererConnect: RendererConnect = {
    postMessage(rendererResponse) {
      onRendererResponse(rendererResponse);
    },

    onMessage(onMessage) {
      messageHandlers = [...messageHandlers, onMessage];
      return () => {
        messageHandlers = messageHandlers.filter(
          handler => handler !== onMessage
        );
      };
    },
  };

  async function postRendererRequest(rendererRequest: RendererRequest) {
    // Simulate async communication between renderer and parent
    await setTimeout(Math.round(Math.random() * 50));
    act(() => {
      messageHandlers.forEach(handler => {
        handler(rendererRequest);
      });
    });
  }

  return { rendererConnect, postRendererRequest };
}
