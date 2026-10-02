// Filter <-> query string sync: list pages keep their filters in
// the URL (replaceState, no history spam) so refresh restores the
// view and filtered views are bookmarkable. Only the keys the page
// owns are touched; `screen` (and `id`) survive untouched. The
// page cursor is deliberately left out: refresh restarts at page
// one with the same filters.

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
