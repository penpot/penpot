export default function () {
  function insertSvg(svg: string) {
    const icon = penpot.createShapeFromSvg(svg);

    if (icon) {
      icon.name = 'Test icon';
      icon.x = penpot.viewport.center.x;
      icon.y = penpot.viewport.center.y;
    }

    return icon;
  }

  const svg = `
  <svg width="300" height="130" xmlns="http://www.w3.org/2000/svg">
    <rect width="200" height="100" x="10" y="10" rx="20" ry="20" fill="blue" />
    Sorry, your browser does not support inline SVG.
  </svg>`;

  insertSvg(svg);
}
