// Dashboard: sidebar, header and a welcome placeholder. T3 hangs the
// error-reports pages off the sidebar built here.

import { renderHeader } from "../components/header.js";
import { renderSidebar } from "../components/sidebar.js";

const NAV_ITEMS = [
  { query: "", label: "Dashboard" },
  { query: "?screen=error-reports", label: "Error reports" },
];

export function dashboardPage(root, { onNavigate }) {
  root.appendChild(renderSidebar(NAV_ITEMS, onNavigate));
  root.appendChild(renderHeader("Dashboard"));

  const main = document.createElement("main");
  main.textContent = "Welcome to the Penpot admin panel.";
  root.appendChild(main);
}
