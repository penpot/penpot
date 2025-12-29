import type { PluginMessageEvent, PluginUIEvent } from './model.js';

penpot.ui.open('FEATHER ICONS PLUGIN', `?theme=${penpot.theme}`, {
  width: 292,
  height: 540,
});

penpot.ui.onMessage<PluginUIEvent>((message) => {
  if (message.type === 'insert-icon') {
    const { name, svg } = message.content;

    if (!svg || !name) {
      return;
    }

    const icon = penpot.createShapeFromSvg(svg);
    if (icon) {
      icon.name = name;
      icon.x = penpot.viewport.center.x;
      icon.y = penpot.viewport.center.y;
    }
  }
});

penpot.on('themechange', (theme) => {
  sendMessage({ type: 'theme', content: theme });
});

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}
