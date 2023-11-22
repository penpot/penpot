import {
  RendererConnect,
  RendererRequest,
  RendererResponse,
  registerPlaygroundShortcuts,
} from 'react-cosmos-core';

export function createPostMessageConnect(): RendererConnect {
  function postMessage(msg: RendererResponse) {
    parent.postMessage(msg, '*');
  }

  return {
    postMessage,

    onMessage(onMessage) {
      function handleMessage(msg: { data: RendererRequest }) {
        onMessage(msg.data);
      }
      window.addEventListener('message', handleMessage, false);

      const removeShortcuts = registerPlaygroundShortcuts(command => {
        postMessage({ type: 'playgroundCommand', payload: { command } });
      });

      return () => {
        window.removeEventListener('message', handleMessage);
        removeShortcuts();
      };
    },
  };
}
