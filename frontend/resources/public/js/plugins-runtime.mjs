export class PluginsElement extends HTMLElement {
  connectedCallback() {
    console.log('PluginsElement.connectedCallback');
  }
}

customElements.define('penpot-plugins', PluginsElement);

// Alternative to message passing
export function initialize(api) {
  console.log("PluginsRuntime:initialize", api)

  api.addListener("foobar", "page", (page) => {
    console.log("Page Changed:", page.name);
  });
};
