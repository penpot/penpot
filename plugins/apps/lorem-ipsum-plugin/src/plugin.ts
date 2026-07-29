import { Text } from '@penpot/plugin-types';
import type {
  PluginMessageEvent,
  PluginUIEvent,
  TextPluginUIEvent,
} from './model.js';
import {
  generateParagraphs,
  generateSentences,
  generateWords,
  generateCharacters,
} from './generator.js';

penpot.ui.open('LOREM IPSUM PLUGIN', `?theme=${penpot.theme}`);

penpot.on('themechange', (theme) => {
  sendMessage({ type: 'theme', content: theme });
});

function getSelectedShapes(): Text[] {
  return penpot.selection.filter((it): it is Text => {
    return penpot.utils.types.isText(it);
  });
}

penpot.on('selectionchange', () => {
  sendMessage({ type: 'selection', content: getSelectedShapes().length });
});

penpot.ui.onMessage<PluginUIEvent>((message) => {
  if (message.type === 'text') {
    generateText(message);

    if (message.autoClose) {
      penpot.closePlugin();
    }
  }
});

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}

function generateText(event: TextPluginUIEvent) {
  const selection = getSelectedShapes();

  if (!selection.length) {
    const text = penpot.createText('lorem ipsum');
    if (text) {
      text.x = penpot.viewport.center.x;
      text.y = penpot.viewport.center.y;
      selection.push(text);
    }
  }

  selection.forEach((it) => {
    switch (event.generationType) {
      case 'paragraphs':
        it.characters = generateParagraphs(event.size, event.startWithLorem);
        break;
      case 'sentences':
        it.characters = generateSentences(event.size, event.startWithLorem);
        break;
      case 'words':
        it.characters = generateWords(event.size, event.startWithLorem);
        break;
      case 'characters':
        it.characters = generateCharacters(event.size, event.startWithLorem);
        break;
    }
  });
}
