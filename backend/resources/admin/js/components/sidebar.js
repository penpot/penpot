// Sidebar navigation: renders a <nav> of links. Labels and queries
// are painted as text; navigation goes through the given callback.
// Hrefs are bare query strings: they resolve against the current
// page, so subpath deployments keep working with no path math.

export function renderSidebar(items, onNavigate, currentQuery = "") {
  const nav = document.createElement("nav");
  nav.className = "admin-sidebar";

  for (const item of items) {
    const link = document.createElement("a");
    link.className = "admin-sidebar-link";
    if (item.query === currentQuery) {
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
