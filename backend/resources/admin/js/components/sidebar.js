// Sidebar navigation: renders a <nav> of links. Labels and queries
// are painted as text; navigation goes through the given callback.
// Hrefs are bare query strings: they resolve against the current
// page, so subpath deployments keep working with no path math. The
// active entry matches by section (`screens`), so filtered list
// URLs and detail pages keep their section highlighted.

export function renderSidebar(items, onNavigate, currentScreen = "") {
  const nav = document.createElement("nav");
  nav.className = "admin-sidebar";

  for (const item of items) {
    const link = document.createElement("a");
    link.className = "admin-sidebar-link";
    if ((item.screens ?? [""]).includes(currentScreen)) {
      link.classList.add("admin-sidebar-link-active");
    }
    link.textContent = item.label;
    link.href = item.query;
    link.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate(item.query);
    });
    nav.appendChild(link);
  }

  return nav;
}
