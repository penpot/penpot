// Bootstrap: paint a loading state, resolve the session, and show
// the matching view. The product login lives at `/login` relative
// to the deployment root, so the redirect strips the panel mount
// (`<root>/admin`) instead of hardcoding a path.

import { checkSuperuserSession } from "./auth.js";
import { renderHeader } from "./components/header.js";
import { dashboardPage } from "./pages/dashboard.js";
import { basePath, navigate, register, start } from "./router.js";

function loadingView(root) {
  root.textContent = "Loading admin…";
}

function loginUrl() {
  return basePath().replace(/\/admin$/, "") + "/login";
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

register("/", loadingView);
register("*", loadingView);

async function boot() {
  start();
  const root = document.getElementById("app");

  let status;
  try {
    status = await checkSuperuserSession();
  } catch {
    root.replaceChildren();
    root.textContent = "Admin — cannot reach the server.";
    return;
  }

  if (status === "anonymous") {
    location.assign(loginUrl());
    return;
  }

  root.replaceChildren();
  if (status === "superuser") {
    dashboardPage(root, { onNavigate: navigate });
  } else {
    deniedView(root);
  }
}

document.addEventListener("DOMContentLoaded", boot);
