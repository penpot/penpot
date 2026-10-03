// Header: renders a <header> with the page title, painted as text.

export function renderHeader(title) {
  const header = document.createElement("header");
  header.className = "admin-header";

  const heading = document.createElement("h1");
  heading.textContent = title;
  header.appendChild(heading);

  return header;
}

// Detail title: `Team > Acme`, with the section part linking back
// to the list (same target as "Back to list"). Everything is
// painted as text.
export function paintDetailTitle(header, { section, query, name, onNavigate }) {
  const heading = header.querySelector("h1");
  heading.replaceChildren();
  const crumb = document.createElement("button");
  crumb.className = "admin-crumb";
  crumb.textContent = section;
  crumb.setAttribute("aria-label", "Back to " + section);
  crumb.addEventListener("click", () => onNavigate(query));
  heading.appendChild(crumb);
  heading.append(" > " + name);
}
