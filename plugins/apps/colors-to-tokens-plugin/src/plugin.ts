import type { PluginMessageEvent, PluginUIEvent } from './model.js';

const defaultSize = {
  width: 410,
  height: 280,
};

penpot.ui.open('COLORS TO TOKENS', `?theme=${penpot.theme}`, {
  width: defaultSize.width,
  height: defaultSize.height,
});

penpot.on('themechange', (theme) => {
  sendMessage({ type: 'theme', content: theme });
});

penpot.ui.onMessage<PluginUIEvent>((message) => {
  if (message.type === 'get-colors') {
    const colors = penpot.library.local.colors.filter(
      (color) => !color.gradient,
    );

    const fileName = penpot.currentFile?.name ?? 'Untitled';

    sendMessage({
      type: 'set-colors',
      colors,
      fileName,
    });
  } else if (message.type === 'resize') {
    if (
      penpot.ui.size?.width === defaultSize.width &&
      penpot.ui.size?.height === defaultSize.height
    ) {
      resize(message.width, message.height);
    }
  } else if (message.type === 'reset') {
    resize(defaultSize.width, defaultSize.height);
  }
});

function resize(width: number, height: number) {
  if ('resize' in penpot.ui) {
    (penpot as any).ui.resize(width, height);
  }
}

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}
