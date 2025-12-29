import type { PluginMessageEvent, PluginUIEvent } from './model.js';

penpot.ui.open('CONTRAST PLUGIN', `?theme=${penpot.theme}`, {
  width: 285,
  height: 525,
});

penpot.ui.onMessage<PluginUIEvent>((message) => {
  if (message.type === 'ready') {
    sendMessage({
      type: 'init',
      content: {
        theme: penpot.theme,
        selection: penpot.selection,
      },
    });

    initEvents();
  }
});

penpot.on('selectionchange', () => {
  const shapes = penpot.selection;
  sendMessage({ type: 'selection', content: shapes });

  initEvents();
});

let listeners: symbol[] = [];

function initEvents() {
  listeners.forEach((listener) => {
    penpot.off(listener);
  });

  listeners = penpot.selection.map((shape) => {
    return penpot.on(
      'shapechange',
      () => {
        const shapes = penpot.selection;
        sendMessage({ type: 'selection', content: shapes });
      },
      { shapeId: shape.id },
    );
  });
}

penpot.on('themechange', () => {
  const theme = penpot.theme;
  sendMessage({ type: 'theme', content: theme });
});

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}
