// Error reports list: filterable table with pagination. Every value
// is painted as text through `renderTable`; rows navigate to the
// detail page. JSON responses use camelCase (`nextSince`, …).

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";

const SOURCES = ["logging", "audit-log", "rlimit", "legacy-v1", "legacy-v2"];
const PAGE_SIZE = 50;

const COLUMNS = [
  { key: "createdAt", label: "Date" },
  { key: "source", label: "Source" },
  { key: "kind", label: "Kind" },
  { key: "hint", label: "Hint" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

export function errorReportsPage(root, { onNavigate }) {
  const state = {
    source: "",
    hint: "",
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
  };

  root.appendChild(renderHeader("Error reports"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const sourceSelect = document.createElement("select");
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
  controls.appendChild(sourceSelect);

  const hintInput = document.createElement("input");
  hintInput.type = "search";
  hintInput.placeholder = "Search hint…";
  controls.appendChild(hintInput);

  const searchButton = document.createElement("button");
  searchButton.textContent = "Search";
  searchButton.addEventListener("click", () => {
    state.source = sourceSelect.value;
    state.hint = hintInput.value.trim();
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(searchButton);
  root.appendChild(controls);

  const tableWrap = document.createElement("div");
  root.appendChild(tableWrap);

  const pager = document.createElement("div");
  const nextButton = document.createElement("button");
  nextButton.textContent = "Next page";
  nextButton.addEventListener("click", () => load({ append: true }));
  pager.appendChild(nextButton);
  root.appendChild(pager);

  function paint() {
    tableWrap.replaceChildren();
    const rows = state.items.map((item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      const item = state.items[i];
      row.addEventListener("click", () =>
        onNavigate("?screen=error-report&id=" + encodeURIComponent(item.id)));
    }
    tableWrap.appendChild(table);
    nextButton.disabled = state.loading || state.nextSince === null;
    searchButton.disabled = state.loading;
  }

  async function load({ append = false } = {}) {
    state.loading = true;
    paint();
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
