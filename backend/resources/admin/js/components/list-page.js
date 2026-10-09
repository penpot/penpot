// Shared list page for the admin panel: lookup input, deleted
// filter, count line, table, and cursor pager in one place. The four
// admin lists (users, teams, projects, files) are thin configs over
// this helper instead of four copies of the same controls/paint/load
// loop. See `filters.js` for the deleted-filter contract (the `deleted`
// URL key carries "", "active" or "deleted"; the RPC takes a boolean).
// JSON responses use camelCase (`nextSince`, …).
//
// A page config looks like:
//
//   listPage(root, {
//     title: "Teams",
//     noun: "teams",              // deleted-select "All …" label
//     itemNoun: "team",           // count line singular
//     emptyText: "No teams match the current filters.",
//     columns: COLUMNS,
//     rpcCommand: "get-teams",
//     loadErrorText: "Could not load teams.",
//     lookupPlaceholder: "Look up by id…",
//     lookupAriaLabel: "Look up by team id",
//     detailKind: "team",         // detailUrl kind for row clicks
//     readRpcParams: (state) => (state.lookup ? { id: state.lookup } : {}),
//     decorateRow: (item) => ({ ...item }),
//     patchRow: (rowEl, row, item, { cellByKey }) => { … },
//     onNavigate,
//   })
//
// Optional extras: `extraQueryKeys` (URL state beyond lookup/deleted,
// e.g. teamId), `initialState`/`resetExtra` for that state,
// `beforeLoad` (async name resolution), `refreshHeader` (title crumbs),
// `extraControls` (bulk/import buttons), `paintExtra` (per-paint tweaks
// like disabling import while loading), `tableClass`, and
// `hideEmptyTable` (projects/files only render the table with rows).

import { rpc } from "../api.js";
import { deletedParams, deletedSelect } from "./filters.js";
import { renderHeader } from "./header.js";
import { renderTable } from "./table.js";
import { showToast } from "./toast.js";
import { readQuery, writeQuery, detailUrl } from "../url.js";

const PAGE_SIZE = 25;

// Ids are UUIDs (8-4-4-4-12 hex). Anything without "@" that does
// not look like one cannot match: the RPC would only answer with a
// generic load error, so the page says so inline instead and skips
// the call (Task 4, review finding F3).
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function listPage(root, config) {
  const {
    title,
    noun,
    itemNoun,
    emptyText,
    columns,
    rpcCommand,
    loadErrorText,
    lookupPlaceholder,
    lookupAriaLabel,
    detailKind,
    extraQueryKeys = [],
    initialState = () => ({}),
    resetExtra = () => {},
    readRpcParams = () => ({}),
    decorateRow = (item) => ({ ...item }),
    patchRow = () => {},
    tableClass = null,
    hideEmptyTable = false,
    extraControls = null,
    paintExtra = null,
    refreshHeader = null,
    beforeLoad = null,
    onNavigate,
  } = config;

  const fromUrl = readQuery(["lookup", "deleted", ...extraQueryKeys]);
  const state = {
    lookup: fromUrl.lookup ?? "",
    deletedFilter: fromUrl.deleted ?? "",
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
    invalidLookup: false,
    ...initialState(fromUrl),
  };

  root.appendChild(renderHeader(title));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  function submit() {
    state.lookup = lookupInput.value.trim();
    state.deletedFilter = deletedFilterEl.value;
    state.nextSince = null;
    state.nextId = null;
    load();
  }

  const lookupInput = document.createElement("input");
  lookupInput.className = "admin-input";
  lookupInput.type = "search";
  lookupInput.placeholder = lookupPlaceholder;
  lookupInput.setAttribute("aria-label", lookupAriaLabel);
  lookupInput.value = state.lookup;
  lookupInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      submit();
    }
  });
  controls.appendChild(lookupInput);

  const deletedFilterEl = deletedSelect(noun, state.deletedFilter);
  controls.appendChild(deletedFilterEl);

  const lookupButton = document.createElement("button");
  lookupButton.className = "admin-button";
  lookupButton.textContent = "Look up";
  lookupButton.addEventListener("click", submit);
  controls.appendChild(lookupButton);

  const clearButton = document.createElement("button");
  clearButton.className = "admin-button admin-button-ghost";
  clearButton.textContent = "Clear";
  clearButton.addEventListener("click", () => {
    lookupInput.value = "";
    deletedFilterEl.value = "";
    state.lookup = "";
    state.deletedFilter = "";
    resetExtra(state);
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(clearButton);

  if (extraControls) {
    extraControls(controls, { state, onNavigate, reload: () => load() });
  }
  root.appendChild(controls);

  const countLine = document.createElement("p");
  countLine.className = "admin-count admin-count-spaced";
  root.appendChild(countLine);

  const tableWrap = document.createElement("div");
  root.appendChild(tableWrap);

  const pager = document.createElement("div");
  pager.className = "admin-pager";
  const nextButton = document.createElement("button");
  nextButton.className = "admin-button";
  nextButton.textContent = "Next page";
  nextButton.addEventListener("click", () => load({ append: true }));
  pager.appendChild(nextButton);
  root.appendChild(pager);

  // Find a body cell by column key instead of by position, so adding
  // or reordering columns cannot shift badges into the wrong cell.
  function cellByKey(rowEl, key) {
    return rowEl.querySelector(`td[data-key="${key}"]`);
  }

  function paint() {
    tableWrap.replaceChildren();
    if (state.loading && !state.loadedOnce) {
      countLine.textContent = "Loading…";
    } else if (state.items.length === 0 && state.invalidLookup) {
      countLine.textContent =
        `“${state.lookup}” is not a valid id: ids have 8-4-4-4-12 hex characters. Check the paste.`;
    } else if (state.items.length === 0) {
      countLine.textContent = emptyText;
    } else {
      countLine.textContent =
        `Showing ${state.items.length} ${itemNoun}` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    if (refreshHeader) {
      refreshHeader({ state, root, onNavigate });
    }

    if (state.items.length > 0 || !hideEmptyTable) {
      const rows = state.items.map(decorateRow);
      const table = renderTable(columns, rows);
      if (tableClass) {
        table.classList.add(tableClass);
      }
      const bodyRows = table.querySelector("tbody").rows;
      for (let i = 0; i < bodyRows.length; i++) {
        patchRow(bodyRows[i], rows[i], state.items[i], { state, cellByKey });
        bodyRows[i].addEventListener("click", () =>
          onNavigate(detailUrl(detailKind, state.items[i].id)));
      }
      tableWrap.appendChild(table);
    }
    nextButton.disabled = state.loading || state.nextSince === null;
    lookupButton.disabled = state.loading;
    clearButton.disabled = state.loading;
    pager.style.display =
      state.items.length === 0 || state.nextSince === null ? "none" : "";
    if (paintExtra) {
      paintExtra({ state });
    }
  }

  function currentQuery() {
    const query = { lookup: state.lookup, deleted: state.deletedFilter };
    for (const key of extraQueryKeys) {
      query[key] = state[key];
    }
    return query;
  }

  async function load({ append = false } = {}) {
    if (!append && state.lookup && !state.lookup.includes("@") && !UUID_RE.test(state.lookup)) {
      state.items = [];
      state.nextSince = null;
      state.nextId = null;
      state.loadedOnce = true;
      state.invalidLookup = true;
      writeQuery(currentQuery());
      paint();
      return;
    }
    state.invalidLookup = false;
    state.loading = true;
    paint();
    writeQuery(currentQuery());
    const params = { limit: PAGE_SIZE, ...deletedParams(state.deletedFilter), ...readRpcParams(state) };
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      if (beforeLoad) {
        await beforeLoad(state);
      }
      const data = await rpc(rpcCommand, params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast(loadErrorText, "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
