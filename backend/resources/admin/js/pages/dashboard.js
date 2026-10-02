// Dashboard: sidebar, header and a welcome placeholder. T3 hangs the
// error-reports pages off the sidebar built here.

import { renderHeader } from "../components/header.js";
import { renderSidebar } from "../components/sidebar.js";

export function dashboardPage(root, { onNavigate }) {
  root.appendChild(
    renderSidebar([{ path: "/", label: "Dashboard" }], onNavigate)
  );
  root.appendChild(renderHeader("Dashboard"));

  const main = document.createElement("main");
  main.textContent = "Welcome to the Penpot admin panel.";
  root.appendChild(main);
}
