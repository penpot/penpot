// Header: renders a <header> with the page title, painted as text.

export function renderHeader(title) {
  const header = document.createElement("header");
  header.className = "admin-header";

  const heading = document.createElement("h1");
  heading.textContent = title;
  header.appendChild(heading);

  return header;
}
