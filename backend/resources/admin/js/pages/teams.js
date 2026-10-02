// Teams list: searchable table with pagination.
// Every value is painted as text. JSON responses use camelCase
// (`nextSince`, …).

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { readQuery, writeQuery } from "../url.js";

const PAGE_SIZE = 25;

const DEFAULT_BADGES = {
  yes: "admin-badge-status-active",
  no: "admin-badge-status-inactive",
};

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name" },
  { key: "owner", label: "Owner", class: "admin-cell-email admin-cell-left" },
  { key: "totalMembers", label: "Members", class: "admin-cell-count" },
  { key: "createdAt", label: "Created", class: "admin-cell-date admin-cell-right" },
  { key: "isDefault", label: "Default", class: "admin-cell-status admin-cell-center" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

export function defaultCell(value) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (DEFAULT_BADGES[value] || DEFAULT_BADGES.no);
  badge.textContent = value;
  return badge;
}

export function teamsPage(root, { onNavigate }) {
  const fromUrl = readQuery(["search"]);
  const state = {
    search: fromUrl.search ?? "",
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Teams"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const searchInput = document.createElement("input");
  searchInput.className = "admin-input";
  searchInput.type = "search";
  searchInput.placeholder = "Search by name…";
  searchInput.setAttribute("aria-label", "Search by team name");
  searchInput.value = state.search;
  searchInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      searchButton.click();
    }
  });
  controls.appendChild(searchInput);

  const searchButton = document.createElement("button");
  searchButton.className = "admin-button";
  searchButton.textContent = "Search";
  searchButton.addEventListener("click", () => {
    state.search = searchInput.value.trim();
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(searchButton);

  const clearButton = document.createElement("button");
  clearButton.className = "admin-button admin-button-ghost";
  clearButton.textContent = "Clear";
  clearButton.addEventListener("click", () => {
    searchInput.value = "";
    state.search = "";
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
      countLine.textContent = "No teams match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} team` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    const rows = state.items.map((item) => ({
      ...item,
      owner: item.owner ?? "—",
      createdAt: formatDate(item.createdAt),
      isDefault: item.isDefault ? "yes" : "no",
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      row.cells[4].replaceChildren(defaultCell(rows[i].isDefault));
      row.addEventListener("click", () =>
        onNavigate("?screen=team&id=" + encodeURIComponent(state.items[i].id)));
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
    writeQuery({ search: state.search });
    const params = { limit: PAGE_SIZE };
    if (state.search) {
      params.search = state.search;
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      const data = await rpc("get-teams", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load teams.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
