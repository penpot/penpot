main();

function main() {
  createPalette();
  penpot.closePlugin();
}

function createPalette() {
  const colors = penpot.library.local.colors.sort((a, b) =>
    a.name.toLowerCase() > b.name.toLowerCase()
      ? 1
      : a.name.toLowerCase() < b.name.toLowerCase()
        ? -1
        : 0,
  );

  const cols = 4;
  const rows = Math.ceil(colors.length / cols);

  const width = cols * 200 + Math.max(0, cols - 1) * 10 + 20;
  const height = rows * 100 + Math.max(0, rows - 1) * 10 + 20;

  const board = penpot.createBoard();
  board.name = 'Palette';

  const viewport = penpot.viewport;
  board.x = viewport.center.x - width / 2;
  board.y = viewport.center.y - height / 2;

  if (colors.length === 0) {
    // NO colors return
    return;
  }

  board.resize(width, height);
  board.borderRadius = 8;

  // create grid
  const grid = board.addGridLayout();

  for (let i = 0; i < rows; i++) {
    grid.addRow('flex', 1);
  }

  for (let i = 0; i < cols; i++) {
    grid.addColumn('flex', 1);
  }

  grid.alignItems = 'center';
  grid.justifyItems = 'start';
  grid.justifyContent = 'stretch';
  grid.alignContent = 'stretch';
  grid.rowGap = 10;
  grid.columnGap = 10;
  grid.verticalPadding = 10;
  grid.horizontalPadding = 10;

  grid.horizontalSizing = 'auto';

  // create text
  for (let row = 0; row < rows; row++) {
    for (let col = 0; col < cols; col++) {
      const i = row * cols + col;
      const color = colors[i];

      if (i >= colors.length) {
        return;
      }

      const board = penpot.createBoard();
      grid.appendChild(board, row + 1, col + 1);
      board.fills = [color.asFill()];
      board.strokes = [
        { strokeColor: '#000000', strokeOpacity: 0.3, strokeStyle: 'solid' },
      ];

      if (board.layoutChild) {
        board.layoutChild.horizontalSizing = 'fill';
        board.layoutChild.verticalSizing = 'fill';
      }

      const flex = board.addFlexLayout();
      flex.alignItems = 'center';
      flex.justifyContent = 'center';
      flex.verticalPadding = 8;
      flex.horizontalPadding = 8;

      const text = penpot.createText(color.name);
      text.fontWeight = 'bold';
      text.fontVariantId = 'bold';
      text.growType = 'auto-width';
      text.strokes = [
        {
          strokeColor: '#FFFFFF',
          strokeWidth: 1,
          strokeAlignment: 'outer',
          strokeOpacity: 0.5,
          strokeStyle: 'solid',
        },
      ];
      board.appendChild(text);
    }
  }
}
