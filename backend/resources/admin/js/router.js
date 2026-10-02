// Minimal router on the History API: views switch without reloading.
// The server answers every `/admin/*` path with the same index.html,
// so deep links and reloads land back here.
//
// Routes are relative to the panel root, itself derived from this
// module's URL (`<root>/admin/js/router.js`), so subpath deployments
// keep working: register "/" for the panel home, "*" as fallback.

const APP_ROOT = new URL("..", import.meta.url).pathname.replace(/\/$/, "");

// The panel mount point (`<root>/admin`), for building deployment-root
// relatives such as the product login URL.
export function basePath() {
  return APP_ROOT;
}

// Deployment-root relative URL for a panel path (for link hrefs).
export function hrefFor(path) {
  return APP_ROOT + path;
}

function matchPattern(pattern, path) {
  const names = pattern.split("/").filter(Boolean);
  const parts = path.split("/").filter(Boolean);
  if (names.length !== parts.length) {
    return null;
  }
  const params = {};
  for (let i = 0; i < names.length; i++) {
    if (names[i].startsWith(":")) {
      params[names[i].slice(1)] = decodeURIComponent(parts[i]);
    } else if (names[i] !== parts[i]) {
      return null;
    }
  }
  return params;
}

function findRoute(path) {
  if (routes.has(path)) {
    return { handler: routes.get(path), params: {} };
  }
  for (const [pattern, handler] of routes) {
    if (!pattern.includes(":")) {
      continue;
    }
    const params = matchPattern(pattern, path);
    if (params) {
      return { handler, params };
    }
  }
  return { handler: routes.get("*"), params: {} };
}

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
  const { handler, params } = findRoute(currentPath());
  if (handler) {
    root.replaceChildren();
    handler(root, params);
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
