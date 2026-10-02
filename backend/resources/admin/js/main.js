// Bootstrap: paint a loading state, resolve the session, and show
// the matching view. Navigation is a bare query string
// (`?screen=error-reports`, `?screen=error-report&id=…`): it resolves
// against the current page, so subpath deployments keep working and
// there is no router. The product login lives next to the panel
// mount (`<root>/admin/`), hence the `../login` hop.

import { checkSuperuserSession } from "./auth.js";
import { renderHeader } from "./components/header.js";
import { dashboardPage } from "./pages/dashboard.js";
import { errorDetailPage } from "./pages/error-detail.js";
import { errorReportsPage } from "./pages/error-reports.js";

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
  const params = screenParams();
  const navigate = (query) => {
    history.pushState({}, "", query);
    renderAuthed(root);
  };
  if (params.get("screen") === "error-reports") {
    errorReportsPage(root, { onNavigate: navigate });
  } else if (params.get("screen") === "error-report" && params.get("id")) {
    errorDetailPage(root, { id: params.get("id"), onNavigate: navigate });
  } else {
    dashboardPage(root, { onNavigate: navigate });
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
