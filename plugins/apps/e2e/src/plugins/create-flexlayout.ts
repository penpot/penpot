export default function () {
  function createFlexLayout(): void {
    const board = penpot.createBoard();
    board.horizontalSizing = 'auto';
    board.verticalSizing = 'auto';

    board.x = penpot.viewport.center.x;
    board.y = penpot.viewport.center.y;

    const flex = board.addFlexLayout();

    flex.dir = 'column';
    flex.wrap = 'wrap';
    flex.alignItems = 'center';
    flex.justifyContent = 'center';
    flex.verticalPadding = 5;
    flex.horizontalPadding = 5;
    flex.horizontalSizing = 'fill';
    flex.verticalSizing = 'fill';

    board.appendChild(penpot.createRectangle());
    board.appendChild(penpot.createEllipse());
  }

  createFlexLayout();
}
