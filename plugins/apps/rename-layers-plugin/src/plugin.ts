import { PluginMessageEvent } from './app/model';

penpot.ui.open('RENAME LAYER PLUGIN', `?theme=${penpot.theme}`, {
  width: 290,
  height: 550,
});

penpot.on('themechange', (theme) => {
  penpot.ui.sendMessage({ type: 'theme', content: theme });
});

penpot.on('shapechange', () => {
  resetSelection();
});

penpot.ui.onMessage<PluginMessageEvent>((message) => {
  if (message.type === 'ready') {
    resetSelection();
  } else if (message.type === 'replace-text') {
    const blockId = penpot.history.undoBlockBegin();

    const shapes = getShapes();
    const shapesToUpdate = shapes?.filter((shape) => {
      return shape.name.includes(message.content.search);
    });
    shapesToUpdate?.forEach((shape) => {
      shape.name = shape.name.replace(
        // eslint-disable-next-line
        message.content.search,
        message.content.replace,
      );
    });
    updateReplaceTextPreview(message.content.search);

    penpot.history.undoBlockFinish(blockId);
  } else if (message.type === 'preview-replace-text') {
    updateReplaceTextPreview(message.content.search);
  } else if (message.type === 'add-text') {
    const blockId = penpot.history.undoBlockBegin();

    const currentNames = message.content.map((shape) => shape.current);
    const shapes = getShapes();
    const shapesToUpdate = shapes?.filter((shape) =>
      currentNames.includes(shape.name),
    );
    shapesToUpdate?.forEach((shape) => {
      const newText = message.content.find((it) => it.current === shape.name);
      return (shape.name = newText?.new ?? shape.name);
    });

    penpot.history.undoBlockFinish(blockId);

    resetSelection();
  }
});

function getShapes() {
  return penpot.selection.length
    ? penpot.selection
    : penpot.currentPage?.findShapes();
}

function resetSelection() {
  penpot.ui.sendMessage({
    type: 'selection',
    content: {
      selection: getShapes(),
    },
  });
}

function updateReplaceTextPreview(search: string) {
  if (search) {
    const shapes = getShapes();
    const shapesToUpdate = shapes?.filter((shape) => {
      return shape.name.includes(search);
    });
    penpot.ui.sendMessage({
      type: 'selection',
      content: {
        selection: shapesToUpdate,
      },
    });
  } else {
    resetSelection();
  }
}
