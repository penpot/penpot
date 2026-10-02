// Minimal router on the History API: views switch without reloading.
// The server answers every `/admin/*` path with the same index.html,
// so deep links and reloads land back here.
//
// Routes are relative to the panel root, itself derived from this
// module's URL (`<root>/admin/js/router.js`), so subpath deployments
// keep working: register "/" for the panel home, "*" as fallback.

const APP_ROOT = new URL("..", import.meta.url).pathname.replace(/\/$/, "");

const routes = new Map();

export function register(path, handler) {
  routes.set(path, handler);
}

function currentPath() {
  const { pathname } = location;
  if (pathname === APP_ROOT || pathname === APP_ROOT + "/") {
    return "/";
  }
  if (pathname.startsWith(APP_ROOT + "/")) {
    return pathname.slice(APP_ROOT.length);
  }
  return pathname;
}

function render() {
  const root = document.getElementById("app");
  const handler = routes.get(currentPath()) || routes.get("*");
  if (handler) {
    root.replaceChildren();
    handler(root);
  }
}

export function navigate(path) {
  history.pushState({}, "", APP_ROOT + path);
  render();
}

export function start() {
  window.addEventListener("popstate", render);
  render();
}
