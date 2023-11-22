export function getDomContainer(querySelector?: null | string) {
  if (!querySelector) {
    return getFallbackDomContainer();
  }

  const existingContainer = document.querySelector(querySelector);
  if (!existingContainer) {
    console.warn(
      `Query selector "${querySelector}" doesn't match any existing DOM element. ` +
        `Are you using a custom HTML template? ` +
        `Add an element matching "${querySelector}" to your template or change the containerQuerySelector setting.`
    );
    return getFallbackDomContainer();
  }

  return existingContainer;
}

function getFallbackDomContainer() {
  return document.getElementById('root') || createDomContainer();
}

function createDomContainer() {
  const container = document.createElement('div');
  container.setAttribute('id', 'root');
  document.body.appendChild(container);
  return container;
}
