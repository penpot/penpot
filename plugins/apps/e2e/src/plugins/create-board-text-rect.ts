import type { Board, Rectangle, Text } from '@penpot/plugin-types';

export default function () {
  function createText(text: string): Text | undefined {
    const textNode = penpot.createText(text);

    if (!textNode) {
      return;
    }

    textNode.x = penpot.viewport.center.x;
    textNode.y = penpot.viewport.center.y;

    return textNode;
  }

  function createRectangle(): Rectangle {
    const rectangle = penpot.createRectangle();

    rectangle.setPluginData('customKey', 'customValue');

    rectangle.x = penpot.viewport.center.x;
    rectangle.y = penpot.viewport.center.y;

    rectangle.resize(200, 200);

    return rectangle;
  }

  function createBoard(): Board {
    const board = penpot.createBoard();

    board.name = 'Board name';

    board.x = penpot.viewport.center.x;
    board.y = penpot.viewport.center.y;

    board.borderRadius = 8;

    board.resize(300, 300);

    const text = penpot.createText('Hello from board');

    if (!text) {
      throw new Error('Could not create text');
    }

    text.x = 10;
    text.y = 10;
    board.appendChild(text);

    return board;
  }

  createBoard();
  createRectangle();
  createText('Hello from plugin');
}
