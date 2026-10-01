// Dashboard: the panel home. A short welcome plus one card per
// section, so the menu entries have somewhere to land and every
// corner of the panel is one click away.

import { renderHeader } from "../components/header.js";

const SECTIONS = [
  {
    query: "?screen=error-reports",
    title: "Error reports",
    text: "Browse server error reports: filter by source, read traces.",
  },
  {
    query: "?screen=users",
    title: "Users",
    text: "Search profiles, review teams, block or delete accounts.",
  },
  {
    query: "?screen=teams",
    title: "Teams",
    text: "Browse teams, review members and feature flags.",
  },
  {
    query: "?screen=projects",
    title: "Projects",
    text: "Browse projects across teams, restore deleted ones.",
  },
  {
    query: "?screen=files",
    title: "Files",
    text: "Browse files across projects, validate and repair.",
  },
  {
    query: "?screen=virtual-clock",
    title: "Virtual clock",
    text: "Shift this session's clock for testing.",
  },
];

export function dashboardPage(root, { onNavigate }) {
  root.appendChild(renderHeader("Dashboard"));

  const intro = document.createElement("p");
  intro.className = "admin-count";
  intro.textContent = "Welcome to the Penpot admin panel. Pick a section:";
  root.appendChild(intro);

  const cards = document.createElement("div");
  cards.className = "admin-cards";
  for (const section of SECTIONS) {
    const card = document.createElement("a");
    card.className = "admin-card";
    card.href = section.query;
    card.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate(section.query);
    });
    const title = document.createElement("strong");
    title.textContent = section.title;
    const text = document.createElement("span");
    text.textContent = section.text;
    card.appendChild(title);
    card.appendChild(text);
    cards.appendChild(card);
  }
  root.appendChild(cards);
}
