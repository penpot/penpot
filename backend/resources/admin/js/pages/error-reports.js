// Error reports list: filterable table with pagination. Every value
// is painted as text; source badges use a fixed class map, never the
// raw value. JSON responses use camelCase (`nextSince`, …).

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { readQuery, writeQuery } from "../url.js";

const SOURCES = ["logging", "audit-log", "rlimit", "legacy-v1", "legacy-v2"];
const PAGE_SIZE = 25;

const SOURCE_BADGES = {
  logging: "admin-badge-source-logging",
  "audit-log": "admin-badge-source-audit",
  rlimit: "admin-badge-source-rlimit",
  "legacy-v1": "admin-badge-source-legacy",
  "legacy-v2": "admin-badge-source-legacy",
};

const COLUMNS = [
  { key: "createdAt", label: "Date", class: "admin-cell-date" },
  { key: "source", label: "Source", class: "admin-cell-source" },
  { key: "kind", label: "Kind", class: "admin-cell-kind" },
  { key: "hint", label: "Hint", class: "admin-cell-hint" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

function sourceCell(source) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (SOURCE_BADGES[source] || "admin-badge-source-legacy");
  badge.textContent = source;
  return badge;
}

export function errorReportsPage(root, { onNavigate }) {
  const fromUrl = readQuery(["source", "hint"]);
  const state = {
    source: SOURCES.includes(fromUrl.source) ? fromUrl.source : "",
    hint: fromUrl.hint ?? "",
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Error reports"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const sourceSelect = document.createElement("select");
  sourceSelect.className = "admin-select";
  sourceSelect.setAttribute("aria-label", "Filter by source");
  const allOption = document.createElement("option");
  allOption.value = "";
  allOption.textContent = "All sources";
  sourceSelect.appendChild(allOption);
  for (const source of SOURCES) {
    const option = document.createElement("option");
    option.value = source;
    option.textContent = source;
    sourceSelect.appendChild(option);
  }
  sourceSelect.value = state.source;
  controls.appendChild(sourceSelect);

  const hintInput = document.createElement("input");
  hintInput.className = "admin-input";
  hintInput.type = "search";
  hintInput.placeholder = "Search hint…";
  hintInput.setAttribute("aria-label", "Search by hint");
  hintInput.value = state.hint;
  hintInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      searchButton.click();
    }
  });
  controls.appendChild(hintInput);

  const searchButton = document.createElement("button");
  searchButton.className = "admin-button";
  searchButton.textContent = "Search";
  searchButton.addEventListener("click", () => {
    state.source = sourceSelect.value;
    state.hint = hintInput.value.trim();
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(searchButton);

  const clearButton = document.createElement("button");
  clearButton.className = "admin-button admin-button-ghost";
  clearButton.textContent = "Clear";
  clearButton.addEventListener("click", () => {
    sourceSelect.value = "";
    hintInput.value = "";
    state.source = "";
    state.hint = "";
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(clearButton);
  root.appendChild(controls);

  const countLine = document.createElement("p");
  countLine.className = "admin-count";
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

  function paint() {
    tableWrap.replaceChildren();
    if (state.loading && !state.loadedOnce) {
      countLine.textContent = "Loading…";
    } else if (state.items.length === 0) {
      countLine.textContent = "No error reports match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} report` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    const rows = state.items.map((item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      const item = state.items[i];
      row.cells[1].replaceChildren(sourceCell(item.source));
      row.addEventListener("click", () =>
        onNavigate("?screen=error-report&id=" + encodeURIComponent(item.id)));
    }
    tableWrap.appendChild(table);
    nextButton.disabled = state.loading || state.nextSince === null;
    searchButton.disabled = state.loading;
    clearButton.disabled = state.loading;
    pager.style.display =
      state.items.length === 0 || state.nextSince === null ? "none" : "";
  }

  async function load({ append = false } = {}) {
    state.loading = true;
    paint();
    writeQuery({ source: state.source, hint: state.hint });
    const params = { limit: PAGE_SIZE };
    if (state.source) {
      params.source = state.source;
    }
    if (state.hint) {
      params.hint = state.hint;
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      const data = await rpc("get-admin-error-reports", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load error reports.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
