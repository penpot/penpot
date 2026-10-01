// Jobs list: name and status filters with keyset pagination.
// Every value is painted as text; status and kind badges use fixed
// class maps, never the raw value. JSON responses use camelCase
// (`nextSince`, …). Modelled on `error-reports.js`: this list has no
// `deleted_at` column, so the shared `list-page.js` (built around
// the tri-state deleted filter) does not fit.

import { rpc } from "../api.js";
import { jobKindCell, jobStatusCell } from "../components/badges.js";
import { formatDate } from "../components/date.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { readQuery, writeQuery, detailUrl } from "../url.js";

// Every job-def name the backend can produce today. The backend
// accepts any text for the filter; this list only feeds the
// dropdown, with "All" for everything else (including future names).
const NAMES = [
  "audit-log-archive",
  "audit-log-gc",
  "delete-object",
  "demo-purge",
  "export-binfile",
  "file-gc",
  "file-gc-scheduler",
  "import-binfile",
  "jobs-gc",
  "objects-gc",
  "offload-file-data",
  "process-webhook-event",
  "restore-object",
  "run-webhook",
  "sendmail",
  "session-gc",
  "storage-gc-deleted",
  "storage-gc-touched",
  "storage-pending-gc",
  "telemetry",
];

const STATUSES = [
  "new",
  "scheduled",
  "running",
  "retry",
  "completed",
  "failed",
  "cancelled",
  "aborted",
];

const PAGE_SIZE = 25;

const COLUMNS = [
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "name", label: "Name", class: "admin-cell-kind" },
  { key: "queue", label: "Queue", class: "admin-cell-kind" },
  { key: "kind", label: "Kind", class: "admin-cell-status" },
  { key: "status", label: "Status", class: "admin-cell-status" },
  { key: "retries", label: "Retries", class: "admin-cell-status" },
  { key: "scheduledAt", label: "Scheduled", class: "admin-cell-date" },
];

export function jobsPage(root, { onNavigate }) {
  const fromUrl = readQuery(["name", "status", "lookup"]);
  const state = {
    name: NAMES.includes(fromUrl.name) ? fromUrl.name : "",
    status: STATUSES.includes(fromUrl.status) ? fromUrl.status : "",
    lookup: fromUrl.lookup ?? "",
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Jobs"));

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const lookupInput = document.createElement("input");
  lookupInput.className = "admin-input";
  lookupInput.type = "search";
  lookupInput.placeholder = "Look up by id…";
  lookupInput.setAttribute("aria-label", "Look up by job id");
  lookupInput.value = state.lookup;
  lookupInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      submit();
    }
  });
  controls.appendChild(lookupInput);

  const nameSelect = document.createElement("select");
  nameSelect.className = "admin-select";
  nameSelect.setAttribute("aria-label", "Filter by job name");
  const allNames = document.createElement("option");
  allNames.value = "";
  allNames.textContent = "All names";
  nameSelect.appendChild(allNames);
  for (const name of NAMES) {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name;
    nameSelect.appendChild(option);
  }
  nameSelect.value = state.name;
  controls.appendChild(nameSelect);

  const statusSelect = document.createElement("select");
  statusSelect.className = "admin-select";
  statusSelect.setAttribute("aria-label", "Filter by job status");
  const allStatuses = document.createElement("option");
  allStatuses.value = "";
  allStatuses.textContent = "All statuses";
  statusSelect.appendChild(allStatuses);
  for (const status of STATUSES) {
    const option = document.createElement("option");
    option.value = status;
    option.textContent = status;
    statusSelect.appendChild(option);
  }
  statusSelect.value = state.status;
  controls.appendChild(statusSelect);

  function submit() {
    state.lookup = lookupInput.value.trim();
    state.name = nameSelect.value;
    state.status = statusSelect.value;
    state.nextSince = null;
    state.nextId = null;
    load();
  }

  const searchButton = document.createElement("button");
  searchButton.className = "admin-button";
  searchButton.textContent = "Search";
  searchButton.title = "Apply the current filters.";
  searchButton.addEventListener("click", submit);
  controls.appendChild(searchButton);

  const clearButton = document.createElement("button");
  clearButton.className = "admin-button admin-button-ghost";
  clearButton.textContent = "Clear";
  clearButton.title = "Clear all filters.";
  clearButton.addEventListener("click", () => {
    lookupInput.value = "";
    nameSelect.value = "";
    statusSelect.value = "";
    state.lookup = "";
    state.name = "";
    state.status = "";
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
  nextButton.title = "Load the next page of jobs.";
  nextButton.addEventListener("click", () => load({ append: true }));
  pager.appendChild(nextButton);
  root.appendChild(pager);

  function paint() {
    tableWrap.replaceChildren();
    if (state.loading && !state.loadedOnce) {
      countLine.textContent = "Loading…";
    } else if (state.items.length === 0) {
      countLine.textContent = "No jobs match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} job` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    const rows = state.items.map((item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
      scheduledAt: formatDate(item.scheduledAt),
      retries: `${item.retryNum}/${item.maxRetries}`,
    }));
    const table = renderTable(COLUMNS, rows);
    const bodyRows = table.querySelector("tbody").rows;
    for (let i = 0; i < bodyRows.length; i++) {
      const row = bodyRows[i];
      const item = state.items[i];
      const cells = row.querySelectorAll("td");
      cells[3].replaceChildren(jobKindCell(item.kind));
      cells[4].replaceChildren(jobStatusCell(item.status));
      row.addEventListener("click", () =>
        onNavigate(detailUrl("job", item.id)));
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
    writeQuery({ name: state.name, status: state.status, lookup: state.lookup });
    const params = { limit: PAGE_SIZE };
    if (state.lookup) {
      params.id = state.lookup;
    } else {
      if (state.name) {
        params.name = state.name;
      }
      if (state.status) {
        params.status = state.status;
      }
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
      const data = await rpc("get-jobs", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load jobs.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  paint();
  load();
}
