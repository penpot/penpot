export default function () {
  function group() {
    const selected = penpot.selection;

    if (selected.length && !penpot.utils.types.isGroup(selected[0])) {
      return penpot.group(selected);
    }
  }

  function ungroup() {
    const selected = penpot.selection;

    if (selected.length && penpot.utils.types.isGroup(selected[0])) {
      return penpot.ungroup(selected[0]);
    }
  }

  const rectangle = penpot.createRectangle();
  rectangle.x = penpot.viewport.center.x;
  rectangle.y = penpot.viewport.center.y;
  const rectangle2 = penpot.createRectangle();
  rectangle2.x = penpot.viewport.center.x + 100;
  rectangle2.y = penpot.viewport.center.y + 100;

  penpot.selection = [rectangle, rectangle2];

  group();
  ungroup();
}
