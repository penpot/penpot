// Sidebar navigation: renders a <nav> of links. Labels and paths
// are painted as text; navigation goes through the given callback
// so this component never touches the router directly. `href`s are
// deployment-root relatives (for new-tab clicks), built with the
// router helper.

import { hrefFor } from "../router.js";

export function renderSidebar(items, onNavigate) {
  const nav = document.createElement("nav");
  nav.className = "admin-sidebar";

  for (const item of items) {
    const link = document.createElement("a");
    link.className = "admin-sidebar-link";
    link.textContent = item.label;
    link.href = hrefFor(item.path);
    link.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate(item.path);
    });
    nav.appendChild(link);
  }

  return nav;
}
