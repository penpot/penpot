export default function () {
  function createRulerGuides(): void {
    const page = penpot.currentPage;

    if (page) {
      page.addRulerGuide('horizontal', penpot.viewport.center.x);
      page.addRulerGuide('vertical', penpot.viewport.center.y);
    }
  }

  function removeRulerGuides(): void {
    const page = penpot.currentPage;

    if (page) {
      page.removeRulerGuide(page.rulerGuides[0]);
    }
  }

  createRulerGuides();
  removeRulerGuides();
}
