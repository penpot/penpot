// Filter <-> query string sync: list pages keep their filters in
// the URL (replaceState, no history spam) so refresh restores the
// view and filtered views are bookmarkable. Only the keys the page
// owns are touched; `screen` (and `id`) survive untouched. The
// page cursor is deliberately left out: refresh restarts at page
// one with the same filters.
//
// List -> detail navigation carries the list URL in `from`, so
// "Back to list" returns to the same filters instead of a generic
// list. `from` is a plain query string (`?screen=users&search=…`);
// backUrl only trusts the five list screens.

export function readQuery(keys) {
  const query = new URLSearchParams(location.search);
  const out = {};
  for (const key of keys) {
    const value = query.get(key);
    if (value !== null) {
      out[key] = value;
    }
  }
  return out;
}

export function writeQuery(patch) {
  const query = new URLSearchParams(location.search);
  for (const [key, value] of Object.entries(patch)) {
    if (value === "" || value === null || value === undefined) {
      query.delete(key);
    } else {
      query.set(key, value);
    }
  }
  history.replaceState({}, "", "?" + query.toString());
}

const LIST_SCREENS = ["users", "teams", "projects", "files", "error-reports"];

// Wrap a target query with the current list URL, so the detail
// page can send "Back to list" to the same filters. Any nested
// `from` is stripped first: cross-detail hops never build chains.
export function withFrom(base) {
  const target = new URLSearchParams(base.startsWith("?") ? base.slice(1) : base);
  const current = new URLSearchParams(location.search);
  current.delete("from");
  // The detail screens add `id`; the list screens only carry
  // filters. Either way the whole query is the return target.
  const restored = current.toString();
  if (restored !== "") {
    target.set("from", "?" + restored);
  }
  return "?" + target.toString();
}

// Detail link from a list row: `detailUrl("user", id)`.
export function detailUrl(screen, id) {
  return withFrom(
    "?screen=" + encodeURIComponent(screen) +
    "&id=" + encodeURIComponent(id));
}

// Resolve "Back to list": the stored list URL when it points at
// a known list screen, else the generic fallback.
export function backUrl(fallback) {
  const from = new URLSearchParams(location.search).get("from");
  if (from && LIST_SCREENS.some((screen) =>
    from === "?screen=" + screen || from.startsWith("?screen=" + screen + "&"))) {
    return from;
  }
  return fallback;
}
