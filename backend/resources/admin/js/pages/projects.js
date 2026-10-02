// Projects list: searchable table with pagination.
//
// The search matches by name or by exact id. `teamId` is not
// offered as a selector here: it only arrives through the URL,
// from the "view projects" link on a team page, and shows as a
// removable active filter. Every value is painted as text. JSON
// responses use camelCase (`nextSince`, …).

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { readQuery, writeQuery } from "../url.js";

const PAGE_SIZE = 25;

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name" },
  { key: "teamName", label: "Team", class: "admin-cell-left" },
  { key: "totalFiles", label: "Files", class: "admin-cell-count" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date admin-cell-right" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

export function projectsPage(root, { onNavigate }) {
  const fromUrl = readQuery(["search", "teamId"]);
  const state = {
    search: fromUrl.search ?? "",
    teamId: fromUrl.teamId ?? null,
    teamName: null,
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Projects"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const searchInput = document.createElement("input");
  searchInput.className = "admin-input";
  searchInput.type = "search";
  searchInput.placeholder = "Search by name or id…";
  searchInput.setAttribute("aria-label", "Search by project name or id");
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

  const teamFilter = document.createElement("div");
  teamFilter.className = "admin-controls";
  teamFilter.hidden = true;
  const teamChip = document.createElement("span");
  teamChip.className = "admin-count";
  teamFilter.appendChild(teamChip);
  const teamClear = document.createElement("button");
  teamClear.className = "admin-button admin-button-ghost";
  teamClear.textContent = "Show all teams";
  teamClear.addEventListener("click", () => {
    state.teamId = null;
    state.teamName = null;
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  teamFilter.appendChild(teamClear);
  root.appendChild(teamFilter);

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
      countLine.textContent = "No projects match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} project` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    teamFilter.hidden = state.teamId === null;
    if (state.teamId !== null) {
      teamChip.textContent = "Team filter: " + (state.teamName ?? state.teamId);
    }

    const rows = state.items.map((item) => ({
      ...item,
      totalFiles: String(item.totalFiles ?? 0),
      modifiedAt: formatDate(item.modifiedAt),
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      row.addEventListener("click", () =>
        onNavigate("?screen=project&id=" + encodeURIComponent(state.items[i].id)));
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
    writeQuery({ search: state.search, teamId: state.teamId });
    const params = { limit: PAGE_SIZE };
    if (state.search) {
      params.search = state.search;
    }
    if (state.teamId) {
      params.teamId = state.teamId;
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      if (state.teamId && state.teamName === null) {
        try {
          const team = await rpc("get-team", { id: state.teamId });
          state.teamName = team.name;
        } catch {
          state.teamName = null;
        }
      }
      const data = await rpc("get-projects", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load projects.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
