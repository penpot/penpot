import { GridLayout } from '@penpot/plugin-types';
import { PluginMessageEvent, TablePluginEvent } from './app/model';

penpot.ui.open('TABLE PLUGIN', `?theme=${penpot.theme}`, {
  width: 280,
  height: 610,
});

penpot.ui.onMessage<PluginMessageEvent>((message) => {
  pluginData(message);

  if (message.type === 'table') {
    let numRows = 0;
    let numCols = 0;
    if (message.content.type === 'import' && message.content.import) {
      numRows = message.content.import.length;
      numCols = message.content.import[0].length;
    } else if (message.content.new) {
      numRows = message.content.new.row;
      numCols = message.content.new.column;
    }

    const board = penpot.createBoard();
    board.name = 'Table';

    const viewport = penpot.viewport;
    board.x = viewport.center.x - 150;
    board.y = viewport.center.y - 200;
    board.resize(numCols * 160, numRows * 50);
    board.borderRadius = 8;

    // create grid
    const grid = board.addGridLayout();

    for (let i = 0; i < numRows; i++) {
      grid.addRow('flex', 1);
    }

    for (let i = 0; i < numCols; i++) {
      grid.addColumn('flex', 1);
    }

    grid.alignItems = 'center';
    grid.justifyItems = 'start';
    grid.justifyContent = 'stretch';
    grid.alignContent = 'stretch';

    // create text
    for (let row = 0; row < numRows; row++) {
      for (let col = 0; col < numCols; col++) {
        if (numRows * numCols >= 25) {
          createGroupCell(grid, numRows, numCols, row, col, message);
        } else {
          createFlexCell(grid, numRows, numCols, row, col, message);
        }
      }
    }
    penpot.closePlugin();
  }
});

function createGroupCell(
  grid: GridLayout,
  numRows: number,
  numCols: number,
  row: number,
  col: number,
  message: TablePluginEvent,
) {
  const bg = penpot.createRectangle();
  bg.x = 0;
  bg.y = 0;
  bg.resize(100, 100);

  if (col === 0 && row === 0) {
    bg.borderRadiusTopLeft = 8;
  } else if (col === 0 && row === numRows - 1) {
    bg.borderRadiusBottomRight = 8;
  } else if (col === numCols - 1 && row === 0) {
    bg.borderRadiusTopRight = 8;
  } else if (col === numCols - 1 && row === numRows - 1) {
    bg.borderRadiusBottomRight = 8;
  }

  if (message.content.options.alternateRows && !(row % 2)) {
    bg.fills = [{ fillColor: '#f8f9fc' }];
  } else {
    bg.fills = [{ fillColor: '#ffffff' }];
  }

  if (
    (message.content.options.filledHeaderRow && row === 0) ||
    (message.content.options.filledHeaderColumn && col === 0)
  ) {
    bg.fills = [{ fillColor: '#d9dfea' }];
  }

  if (message.content.options.borders) {
    bg.strokes = [
      {
        strokeColor: '#d4dadc',
        strokeStyle: 'solid',
        strokeWidth: 0.5,
        strokeAlignment: 'center',
      },
    ];
  }

  let text;
  if (message.content.type === 'import' && message.content.import) {
    text = penpot.createText(message.content.import[row][col]);
  } else if (message.content.new) {
    text = row === 0 ? penpot.createText('Header') : penpot.createText('Cell');
  }

  if (text) {
    text.x = 20;
    text.y = 10;
    text.resize(60, 80);
    text.verticalAlign = 'center';
    text.growType = 'auto-height';
    text.fontFamily = 'Work Sans';
    text.fontId = 'gfont-work-sans';
    text.fontVariantId = row === 0 ? '500' : 'regular';
    text.fontSize = '12';
    text.fontWeight = row === 0 ? '500' : '400';

    const group = penpot.group([bg, text]);
    if (group) {
      text.constraintsHorizontal = 'leftright';
      text.constraintsVertical = 'topbottom';

      grid.appendChild(group, row + 1, col + 1);
      if (group.layoutChild) {
        group.layoutChild.horizontalSizing = 'fill';
        group.layoutChild.verticalSizing = 'fill';
      }
    }
  }
}

function createFlexCell(
  grid: GridLayout,
  numRows: number,
  numCols: number,
  row: number,
  col: number,
  message: TablePluginEvent,
) {
  const board = penpot.createBoard();

  if (col === 0 && row === 0) {
    board.borderRadiusTopLeft = 8;
  } else if (col === 0 && row === numRows - 1) {
    board.borderRadiusBottomRight = 8;
  } else if (col === numCols - 1 && row === 0) {
    board.borderRadiusTopRight = 8;
  } else if (col === numCols - 1 && row === numRows - 1) {
    board.borderRadiusBottomRight = 8;
  }

  grid.appendChild(board, row + 1, col + 1);

  if (board.layoutChild) {
    board.layoutChild.horizontalSizing = 'fill';
    board.layoutChild.verticalSizing = 'fill';
  }

  if (message.content.options.alternateRows && !(row % 2)) {
    board.fills = [{ fillColor: '#f8f9fc' }];
  }

  if (
    (message.content.options.filledHeaderRow && row === 0) ||
    (message.content.options.filledHeaderColumn && col === 0)
  ) {
    board.fills = [{ fillColor: '#d9dfea' }];
  }

  if (message.content.options.borders) {
    board.strokes = [
      {
        strokeColor: '#d4dadc',
        strokeStyle: 'solid',
        strokeWidth: 0.5,
        strokeAlignment: 'center',
      },
    ];
  }

  const flex = board.addFlexLayout();
  flex.alignItems = 'center';
  flex.justifyContent = 'start';
  flex.verticalPadding = 10;
  flex.horizontalPadding = 20;

  let text;
  if (message.content.type === 'import' && message.content.import) {
    text = penpot.createText(message.content.import[row][col]);
  } else if (message.content.new) {
    text = row === 0 ? penpot.createText('Header') : penpot.createText('Cell');
  }

  if (text) {
    text.growType = 'auto-height';
    text.fontFamily = 'Work Sans';
    text.fontId = 'gfont-work-sans';
    text.fontVariantId = row === 0 ? '500' : 'regular';
    text.fontSize = '12';
    text.fontWeight = row === 0 ? '500' : '400';
    board.appendChild(text);
    if (text.layoutChild) {
      text.layoutChild.horizontalSizing = 'fill';
      text.layoutChild.verticalSizing = 'fix';
    }
  }
}

function pluginData(message: PluginMessageEvent) {
  if (message.type === 'tableconfig') {
    const { type, options } = message.content;
    const page = penpot.currentPage;

    if (type === 'save') {
      page?.setPluginData('table-plugin', JSON.stringify(options));
    } else if (message.content.type === 'retrieve') {
      const data = page?.getPluginData('table-plugin');
      const options = data ? JSON.parse(data) : null;

      sendMessage({
        type: 'tableconfig',
        content: {
          type: 'retrieve',
          options,
        },
      });
    }
  }
}

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}
