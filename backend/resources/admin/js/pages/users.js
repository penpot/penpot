// Users list: searchable table with flag filters and pagination.
// Every value is painted as text; status badges use a fixed class
// map, never the raw value. JSON responses use camelCase
// (`nextSince`, …).

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";

const PAGE_SIZE = 50;

const STATUS_BADGES = {
  blocked: "admin-badge-status-blocked",
  inactive: "admin-badge-status-inactive",
  demo: "admin-badge-status-demo",
  active: "admin-badge-status-active",
};

const COLUMNS = [
  { key: "email", label: "Email", class: "admin-cell-email" },
  { key: "fullname", label: "Name", class: "admin-cell-name" },
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

const FLAG_FILTERS = [
  { param: "isBlocked", label: "Blocked" },
  { param: "isActive", label: "Active" },
  { param: "isDemo", label: "Demo" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

function statusOf(item) {
  if (item.isBlocked) {
    return "blocked";
  }
  if (item.isDemo) {
    return "demo";
  }
  if (!item.isActive) {
    return "inactive";
  }
  return "active";
}

function statusCell(status) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (STATUS_BADGES[status] || STATUS_BADGES.active);
  badge.textContent = status;
  return badge;
}

function flagSelect(filter) {
  const select = document.createElement("select");
  select.className = "admin-select";
  select.setAttribute("aria-label", `Filter by ${filter.label.toLowerCase()}`);
  for (const [value, label] of [
    ["", `All ${filter.label.toLowerCase()} states`],
    ["true", `${filter.label}: yes`],
    ["false", `${filter.label}: no`],
  ]) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.appendChild(option);
  }
  return select;
}

export function usersPage(root, { onNavigate }) {
  const state = {
    search: "",
    flags: { isBlocked: "", isActive: "", isDemo: "" },
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Users"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const searchInput = document.createElement("input");
  searchInput.className = "admin-input";
  searchInput.type = "search";
  searchInput.placeholder = "Search email or name…";
  searchInput.setAttribute("aria-label", "Search by email or name");
  controls.appendChild(searchInput);

  const selects = {};
  for (const filter of FLAG_FILTERS) {
    const select = flagSelect(filter);
    selects[filter.param] = select;
    controls.appendChild(select);
  }

  const searchButton = document.createElement("button");
  searchButton.className = "admin-button";
  searchButton.textContent = "Search";
  searchButton.addEventListener("click", () => {
    state.search = searchInput.value.trim();
    for (const filter of FLAG_FILTERS) {
      state.flags[filter.param] = selects[filter.param].value;
    }
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
    for (const filter of FLAG_FILTERS) {
      selects[filter.param].value = "";
      state.flags[filter.param] = "";
    }
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
      countLine.textContent = "No users match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} user` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    const rows = state.items.map((item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
      status: statusOf(item),
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      row.cells[3].replaceChildren(statusCell(rows[i].status));
      row.addEventListener("click", () =>
        onNavigate("?screen=user&id=" + encodeURIComponent(state.items[i].id)));
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
    const params = { limit: PAGE_SIZE };
    if (state.search) {
      params.search = state.search;
    }
    for (const filter of FLAG_FILTERS) {
      if (state.flags[filter.param] !== "") {
        params[filter.param] = state.flags[filter.param] === "true";
      }
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      const data = await rpc("get-admin-profiles", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load users.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
