import { Variants } from '@penpot/plugin-types';

const GRID = [5, 5];

penpot.ui.open('Plugin name', '', {
  width: 500,
  height: 600,
});

penpot.ui.onMessage<{ content: string; data: unknown }>(async (message) => {
  if (message.content === 'close') {
    penpot.closePlugin();
  } else if (message.content === 'ready') {
    init();
  } else if (message.content === 'change-name') {
    changeName(message.data as { id: string; name: string });
  } else if (message.content === 'create-rect') {
    createRect();
  } else if (message.content === 'move-x') {
    moveX(message.data as { id: string });
  } else if (message.content === 'move-y') {
    moveY(message.data as { id: string });
  } else if (message.content === 'resize-w') {
    resizeW(message.data as { id: string });
  } else if (message.content === 'resize-h') {
    resizeH(message.data as { id: string });
  } else if (message.content === 'lorem-ipsum') {
    loremIpsum();
  } else if (message.content === 'add-icon') {
    addIcon();
  } else if (message.content === 'create-grid') {
    createGrid();
  } else if (message.content === 'create-colors') {
    createColors();
  } else if (message.content === 'increase-counter') {
    increaseCounter();
  } else if (message.content === 'word-styles') {
    wordStyles();
  } else if (message.content === 'rotate-selection') {
    rotateSelection();
  } else if (message.content === 'create-image-data') {
    const { data, mimeType } = message.data as {
      data: Uint8Array;
      mimeType: string;
    };
    createImage(data, mimeType);
  } else if (message.content === 'create-margins') {
    createMargins();
  } else if (message.content === 'add-comment') {
    addComment();
  } else if (message.content === 'export-file') {
    exportFile();
  } else if (message.content === 'export-selected') {
    exportSelected();
  } else if (message.content === 'resize-modal') {
    resizeModal();
  } else if (message.content === 'save-localstorage') {
    saveLocalStorage();
  } else if (message.content === 'transform-in-variant') {
    transformInVariant();
  } else if (message.content === 'combine-selected-as-variants') {
    combineSelectedAsVariants();
  } else if (message.content === 'add-variant') {
    addVariant();
  } else if (message.content === 'add-property') {
    addProperty();
  } else if (message.content === 'remove-property') {
    removeProperty(message.data as number);
  } else if (message.content === 'rename-property') {
    const { pos, name } = message.data as {
      pos: number;
      name: string;
    };
    renameProperty(pos, name);
  } else if (message.content === 'set-variant-property') {
    const { pos, value } = message.data as {
      pos: number;
      value: string;
    };
    setVariantProperty(pos, value);
  } else if (message.content === 'switch-variant') {
    const { pos, value } = message.data as {
      pos: number;
      value: string;
    };
    switchVariant(pos, value);
  }
});

penpot.on('pagechange', () => {
  const page = penpot.currentPage;
  const shapes = page?.findShapes();

  penpot.ui.sendMessage({
    type: 'page',
    content: { page, shapes },
  });
});

penpot.on('filechange', () => {
  const file = penpot.currentFile;

  if (!file) {
    return;
  }

  penpot.ui.sendMessage({
    type: 'file',
    content: {
      id: file.id,
    },
  });
});

penpot.on('selectionchange', () => {
  const selection = penpot.selection;
  const data: string | null =
    selection.length === 1 ? selection[0].getPluginData('counter') : null;
  const counter = data ? parseInt(data, 10) : 0;
  penpot.ui.sendMessage({ type: 'selection', content: { selection, counter } });
});

penpot.on('themechange', (theme) => {
  penpot.ui.sendMessage({ type: 'theme', content: theme });
});

function init() {
  const page = penpot.currentPage;
  const file = penpot.currentFile;

  if (!page || !file) {
    return;
  }

  const selection = penpot.selection;
  const data: string | null =
    selection.length === 1 ? selection[0].getPluginData('counter') : null;
  const counter = data ? parseInt(data, 10) : 0;

  penpot.ui.sendMessage({
    type: 'init',
    content: {
      name: page.name,
      pageId: page.id,
      fileId: file.id,
      revn: file.revn,
      theme: penpot.theme,
      selection,
      counter,
    },
  });
}

function changeName(data: { id: string; name: string }) {
  const shape = penpot.currentPage?.getShapeById('' + data.id);
  if (shape) {
    shape.name = data.name;
  }
}

function createRect() {
  const shape = penpot.createRectangle();
  const center = penpot.viewport.center;
  shape.x = center.x;
  shape.y = center.y;

  penpot.on(
    'shapechange',
    (s) => {
      console.log('change', s.name, s.x, s.y);
    },
    {
      shapeId: shape.id,
    },
  );
}

function moveX(data: { id: string }) {
  const shape = penpot.currentPage?.getShapeById('' + data.id);
  if (shape) {
    shape.x += 100;
  }
}

function moveY(data: { id: string }) {
  const shape = penpot.currentPage?.getShapeById('' + data.id);
  if (shape) {
    shape.y += 100;
  }
}

function resizeW(data: { id: string }) {
  const shape = penpot.currentPage?.getShapeById('' + data.id);
  if (shape) {
    shape.resize(shape.width * 2, shape.height);
  }
}

function resizeH(data: { id: string }) {
  const shape = penpot.currentPage?.getShapeById('' + data.id);
  if (shape) {
    shape.resize(shape.width, shape.height * 2);
  }
}

function loremIpsum() {
  const selection = penpot.selection;

  for (const shape of selection) {
    if (penpot.utils.types.isText(shape)) {
      shape.characters = `Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nam id mauris ut felis finibus congue. Ut odio ipsum, condimentum id tellus sit amet, dapibus sagittis ligula. Pellentesque hendrerit, nulla sit amet aliquet scelerisque, orci nunc commodo tellus, quis hendrerit nisl massa non tellus.

Phasellus fringilla tortor elit, ac dictum tellus posuere sodales. Ut eget imperdiet ante. Nunc eros magna, tincidunt non finibus in, tempor elementum nunc. Sed commodo magna in arcu aliquam efficitur.`;
    } else if (penpot.utils.types.isRectangle(shape)) {
      const width = Math.ceil(shape.width);
      const height = Math.ceil(shape.height);
      penpot
        .uploadMediaUrl(
          'placeholder',
          `https://picsum.photos/${width}/${height}`,
        )
        .then((data) => {
          shape.fills = [{ fillOpacity: 1, fillImage: data }];
        })
        .catch((err) => console.error(err));
    }
  }
}

function addIcon() {
  const iconStr = `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <svg
           width="32"
           height="32"
           fill="#aa2727"
           viewBox="0 0 24 24"
           version="1.1"
           id="svg1038"
           xmlns="http://www.w3.org/2000/svg"
           xmlns:svg="http://www.w3.org/2000/svg">
          <defs
             id="defs1042" />
          <path
             d="m 12.036278,21.614293 c -5.3352879,0 -9.6752019,-4.339914 -9.6752019,-9.674229 0,-5.334314 4.339914,-9.6742275 9.6752019,-9.6742275 5.335289,0 9.675202,4.3399135 9.675202,9.6742275 0,5.334315 -4.339913,9.674229 -9.675202,9.674229 z"
             id="path1034-5"
             style="fill:#ffff00;stroke-width:0.973948" />
          <g
             id="g1036">
            <path
               d="m 15.811,10.399 c 0.45,-0.46 -0.25,-1.17 -0.71,-0.71 l -3.56,3.56 c -0.58,-0.58 -1.16,-1.16 -1.73,-1.73 -0.46,-0.46 -1.17,0.25 -0.71,0.71 l 2.08,2.08 c 0.2,0.19 0.52,0.19 0.71,0 z"
               id="path1032" />
            <path
               d="M 12,21.933 C 6.522,21.933 2.066,17.477 2.066,12 2.066,6.523 6.522,2.067 12,2.067 c 5.478,0 9.934,4.456 9.934,9.933 0,5.477 -4.456,9.933 -9.934,9.933 z M 12,3.067 c -4.926,0 -8.934,4.007 -8.934,8.933 0,4.926 4.008,8.933 8.934,8.933 4.926,0 8.934,-4.007 8.934,-8.933 0,-4.926 -4.008,-8.933 -8.934,-8.933 z"
               id="path1034" />
          </g>
</svg>`;
  const shape = penpot.createShapeFromSvg(iconStr);
  if (shape) {
    const center = penpot.viewport.center;
    shape.x = center.x;
    shape.y = center.y;
  }
}

function createGrid() {
  const board = penpot.createBoard();
  board.name = 'Board Grid';

  const viewport = penpot.viewport;
  board.x = viewport.center.x - 150;
  board.y = viewport.center.y - 200;
  board.resize(300, 400);

  // create grid
  const grid = board.addGridLayout();
  const [numRows, numCols] = GRID;

  for (let i = 0; i < numRows; i++) {
    grid.addRow('auto');
  }

  for (let i = 0; i < numCols; i++) {
    grid.addColumn('auto');
  }

  grid.alignItems = 'center';
  grid.justifyItems = 'start';
  grid.justifyContent = 'space-between';
  grid.alignContent = 'stretch';
  grid.rowGap = 1;
  grid.columnGap = 2;
  grid.verticalPadding = 3;
  grid.horizontalPadding = 4;

  // create text
  for (let row = 0; row < numRows; row++) {
    for (let col = 0; col < numCols; col++) {
      const text = penpot.createText(`${row + 1} - ${col + 1}`);
      if (text) {
        text.growType = 'auto-width';
        grid.appendChild(text, row + 1, col + 1);
      }
    }
  }
}

function createColors() {
  const board = penpot.createBoard();
  board.name = 'Palette';

  const viewport = penpot.viewport;
  board.x = viewport.center.x - 150;
  board.y = viewport.center.y - 200;

  const colors = penpot.library.local.colors.sort((a, b) =>
    a.name.toLowerCase() > b.name.toLowerCase()
      ? 1
      : a.name.toLowerCase() < b.name.toLowerCase()
        ? -1
        : 0,
  );

  if (colors.length === 0) {
    // NO colors return
    return;
  }

  const cols = 3;
  const rows = Math.ceil(colors.length / 3);

  const width = cols * 150 + Math.max(0, cols - 1) * 10 + 20;
  const height = rows * 100 + Math.max(0, rows - 1) * 10 + 20;

  board.resize(width, height);
  board.borderRadius = 8;

  // create grid
  const grid = board.addGridLayout();

  for (let i = 0; i < rows; i++) {
    grid.addRow('auto');
  }

  for (let i = 0; i < cols; i++) {
    grid.addColumn('auto');
  }

  grid.alignItems = 'center';
  grid.justifyItems = 'start';
  grid.justifyContent = 'stretch';
  grid.alignContent = 'stretch';
  grid.rowGap = 10;
  grid.columnGap = 10;
  grid.verticalPadding = 10;
  grid.horizontalPadding = 10;

  // These properties are not mandatory, if not defined will apply the default values
  board.shadows = [
    {
      style: 'drop-shadow',
      offsetX: 5,
      offsetY: 5,
      blur: 4,
      spread: 5,
      color: {
        color: '#000000',
        opacity: 0.3,
      },
    },
  ];

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

      const text = penpot.createText(color.name);
      if (text) {
        text.growType = 'auto-width';
        board.appendChild(text);
      }
    }
  }
}

function increaseCounter() {
  const selection = penpot.selection;
  const data: string | null =
    selection.length === 1 ? selection[0].getPluginData('counter') : null;
  let counter = data ? parseInt(data, 10) : 0;
  counter++;

  selection[0].setPluginData('counter', '' + counter);
  penpot.ui.sendMessage({ type: 'update-counter', content: { counter } });
}

function wordStyles() {
  const selection = penpot.selection;

  if (selection.length >= 1 && penpot.utils.types.isText(selection[0])) {
    const shape = selection[0];
    const text = shape.characters;

    const isSplit = (c: string) => !!c.match(/\W/);

    if (text.trim() === '') {
      return;
    }

    let lastWordStart = 0;
    let gettingWord = !isSplit(text[0]);
    let wordProcessed = 0;

    for (let i = 1; i < text.length; i++) {
      if (gettingWord && isSplit(text[i])) {
        if (wordProcessed % 2 === 0) {
          const range = shape.getRange(lastWordStart, i);
          range.fills = [{ fillColor: '#FF0000', fillOpacity: 1 }];
          range.textTransform = 'uppercase';
          range.fontSize = '20';
        }

        wordProcessed++;
        gettingWord = false;
      } else if (!gettingWord && !isSplit(text[i])) {
        lastWordStart = i;
        gettingWord = true;
      }
    }
  }
}

function rotateSelection() {
  const selection = penpot.selection;
  const center = penpot.utils.geometry.center(selection);

  selection.forEach((shape) => {
    shape.rotate(10, center);
  });
}

function createImage(data: Uint8Array, mimeType: string) {
  penpot
    .uploadMediaData('image', data, mimeType)
    .then((data) => {
      const shape = penpot.createRectangle();
      const x = penpot.viewport.center.x - data.width / 2;
      const y = penpot.viewport.center.y - data.height / 2;
      shape.resize(data.width, data.height);
      shape.x = x;
      shape.y = y;
      shape.fills = [{ fillOpacity: 1, fillImage: data }];
    })
    .catch((err) => console.error(err));
}

function createMargins() {
  const page = penpot.currentPage;
  const selected = penpot.selection && penpot.selection[0];

  if (selected && penpot.utils.types.isBoard(selected)) {
    const { width, height } = selected;
    selected.addRulerGuide('vertical', 10);
    selected.addRulerGuide('vertical', width - 10);
    selected.addRulerGuide('horizontal', 10);
    selected.addRulerGuide('horizontal', height - 10);
  } else if (page) {
    console.log('bound', penpot.viewport.bounds);
    const { x, y, width, height } = penpot.viewport.bounds;
    page.addRulerGuide('vertical', x + 100);
    page.addRulerGuide('vertical', x + width - 50);
    page.addRulerGuide('horizontal', y + 100);
    page.addRulerGuide('horizontal', y + height - 50);
  }
}

async function addComment() {
  const shape = penpot.selection[0];

  if (shape) {
    const content = shape.name + ' - ' + Date.now();
    const cthr = await penpot.currentPage?.findCommentThreads();
    const th = cthr && cthr[0];

    if (th) {
      const comms = await th.findComments();
      const first = comms && comms[0];
      if (first) {
        console.log('Reply to thread', content);
        th.reply(content);
      }
    } else {
      console.log('Create new thread', content);
      await penpot.currentPage?.addCommentThread(content, shape.center);
    }
  }
}

async function exportFile() {
  const data = await penpot.currentFile?.export('penpot');

  if (data) {
    penpot.ui.sendMessage({
      type: 'start-download',
      name: 'Export.penpot',
      content: data,
    });
  }
}

async function exportSelected() {
  const selection = await penpot.selection[0];

  if (selection) {
    const data = await selection.export({ type: 'png', skipChildren: true });
    penpot.ui.sendMessage({
      type: 'start-download',
      name: 'export.png',
      content: data,
    });
  }
}

async function resizeModal() {
  penpot.ui.resize(1920, 1080);
}

async function saveLocalStorage() {
  const oldvalue = penpot.localStorage.getItem('test');
  const newvalue = oldvalue ? parseInt(oldvalue, 10) + 1 : 1;
  console.log(newvalue);
  penpot.localStorage.setItem('test', newvalue);
}

function getVariantsFromSelection(): Variants | null {
  const shape = penpot.selection?.[0];
  if (!shape) return null;

  if (penpot.utils.types.isVariantContainer(shape)) {
    return shape.variants;
  } else {
    const component = shape.component();
    if (component && penpot.utils.types.isVariantComponent(component)) {
      return component.variants;
    }
  }
  return null;
}

function transformInVariant() {
  const component = penpot.selection?.[0].component();

  if (component && !component.isVariant()) {
    component.transformInVariant();
  }
}

function combineSelectedAsVariants() {
  if (penpot.selection) {
    const ids: string[] = penpot.selection.map((item) => item.id);
    penpot.selection[0]?.combineAsVariants(ids);
  }
}

function addVariant() {
  const shape = penpot.selection?.[0];
  if (penpot.utils.types.isVariantContainer(shape)) {
    shape.variants?.addVariant();
  }
}

function addProperty() {
  getVariantsFromSelection()?.addProperty();
}

function removeProperty(pos: number) {
  getVariantsFromSelection()?.removeProperty(pos);
}

function renameProperty(pos: number, name: string) {
  getVariantsFromSelection()?.renameProperty(pos, name);
}

function setVariantProperty(pos: number, value: string) {
  const component = penpot.selection && penpot.selection[0].component();

  if (component && penpot.utils.types.isVariantComponent(component)) {
    component.setVariantProperty(pos, value);
  }
}

function switchVariant(pos: number, value: string) {
  const shape = penpot.selection && penpot.selection[0];

  if (shape?.isVariantHead()) {
    shape.switchVariant(pos, value);
  }
}
