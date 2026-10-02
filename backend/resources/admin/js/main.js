// Bootstrap: paint a loading state, resolve the session, and show
// the matching view. Navigation is a bare query string
// (`?screen=error-reports`, `?screen=error-report&id=…`): it resolves
// against the current page, so subpath deployments keep working and
// there is no router. The product login lives next to the panel
// mount (`<root>/admin/`), hence the `../login` hop.

import { checkSuperuserSession } from "./auth.js";
import { renderHeader } from "./components/header.js";
import { renderSidebar } from "./components/sidebar.js";
import { dashboardPage } from "./pages/dashboard.js";
import { errorDetailPage } from "./pages/error-detail.js";
import { errorReportsPage } from "./pages/error-reports.js";
import { userDetailPage } from "./pages/user-detail.js";
import { usersDeletePage } from "./pages/users-delete.js";
import { teamsPage } from "./pages/teams.js";
import { teamDetailPage } from "./pages/team-detail.js";
import { filesPage } from "./pages/files.js";
import { fileDetailPage } from "./pages/file-detail.js";
import { usersPage } from "./pages/users.js";

const NAV_ITEMS = [
  // Home targets the path itself, not an empty query: pushing ""
  // would inherit the current query string and go nowhere.
  { query: location.pathname, label: "Dashboard", screens: [""] },
  { query: "?screen=error-reports", label: "Error reports", screens: ["error-reports", "error-report"] },
  { query: "?screen=users", label: "Users", screens: ["users", "user", "users-delete"] },
  { query: "?screen=teams", label: "Teams", screens: ["teams", "team"] },
  { query: "?screen=files", label: "Files", screens: ["files", "file"] },
];

let sessionStatus = "anonymous";

function screenParams() {
  return new URLSearchParams(location.search);
}

function loginUrl() {
  return new URL("../login", location.href).pathname;
}

function deniedView(root) {
  root.appendChild(renderHeader("Access denied"));

  const message = document.createElement("p");
  message.textContent = "This panel is for instance superusers.";
  root.appendChild(message);

  const login = document.createElement("a");
  login.textContent = "Go to login";
  login.href = loginUrl();
  root.appendChild(login);
}

function renderAuthed(root) {
  root.replaceChildren();
  if (sessionStatus !== "superuser") {
    deniedView(root);
    return;
  }
  const layout = document.createElement("div");
  layout.className = "admin-layout";
  const navigate = (query) => {
    history.pushState({}, "", query);
    renderAuthed(root);
  };
  layout.appendChild(renderSidebar(NAV_ITEMS, navigate, screenParams().get("screen") ?? ""));
  const content = document.createElement("main");
  content.className = "admin-content";
  layout.appendChild(content);
  root.appendChild(layout);

  const params = screenParams();
  if (params.get("screen") === "error-reports") {
    errorReportsPage(content, { onNavigate: navigate });
  } else if (params.get("screen") === "error-report" && params.get("id")) {
    errorDetailPage(content, { id: params.get("id"), onNavigate: navigate });
  } else if (params.get("screen") === "users") {
    usersPage(content, { onNavigate: navigate });
  } else if (params.get("screen") === "user" && params.get("id")) {
    userDetailPage(content, { id: params.get("id"), onNavigate: navigate });
  } else if (params.get("screen") === "users-delete") {
    usersDeletePage(content, { onNavigate: navigate });
  } else if (params.get("screen") === "teams") {
    teamsPage(content, { onNavigate: navigate });
  } else if (params.get("screen") === "team" && params.get("id")) {
    teamDetailPage(content, { id: params.get("id"), onNavigate: navigate });
  } else if (params.get("screen") === "files") {
    filesPage(content, { onNavigate: navigate });
  } else if (params.get("screen") === "file" && params.get("id")) {
    fileDetailPage(content, { id: params.get("id"), onNavigate: navigate });
  } else {
    dashboardPage(content, { onNavigate: navigate });
  }
}

async function boot() {
  const root = document.getElementById("app");
  root.textContent = "Loading admin…";

  try {
    sessionStatus = await checkSuperuserSession();
  } catch {
    root.replaceChildren();
    root.textContent = "Admin — cannot reach the server.";
    return;
  }

  if (sessionStatus === "anonymous") {
    location.assign(loginUrl());
    return;
  }

  renderAuthed(root);
  window.addEventListener("popstate", () => renderAuthed(root));
}

document.addEventListener("DOMContentLoaded", boot);
