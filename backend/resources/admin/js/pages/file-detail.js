// File detail: metadata plus validate, repair, and export.
// The file is located through the exact-id search, so no extra
// detail command is needed. Validating paints the error table;
// repairing asks to type the error count first (like the bulk
// user delete) and snapshots by default; exporting is a plain
// download link with the session cookie. Every value is painted
// as text. An unknown id shows "not found" instead of failing.

import { rpc, transferUrl } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";

const ERROR_COLUMNS = [
  { key: "code", label: "Code", class: "admin-cell-name" },
  { key: "hint", label: "Hint", class: "admin-cell-left" },
  { key: "shapeId", label: "Shape", class: "admin-cell-left" },
  { key: "pageId", label: "Page", class: "admin-cell-left" },
];

export function fileDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("File");
  root.appendChild(header);

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("?screen=files"));
  root.appendChild(back);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  const state = { file: null, errors: null };
  let refreshRepair = () => {};

  load();

  async function load() {
    let found;
    try {
      found = await rpc("get-files", { search: id });
    } catch {
      body.replaceChildren();
      body.textContent = "Could not load the file.";
      showToast("Could not load the file.", "error");
      return;
    }
    const file = (found.items ?? []).find((item) => item.id === id) ?? null;
    if (file === null) {
      body.replaceChildren();
      body.textContent = "File not found.";
      return;
    }
    state.file = file;
    header.querySelector("h1").textContent = file.name;
    body.replaceChildren();
    body.appendChild(infoBlock(file));
    body.appendChild(validateBlock());
    body.appendChild(repairBlock());
    body.appendChild(exportBlock());
  }

  function infoBlock(file) {
    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const [key, label] of [["id", "Id"], ["projectName", "Project"], ["teamName", "Team"]]) {
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = String(file[key] ?? "—");
      list.appendChild(term);
      list.appendChild(desc);
    }
    return list;
  }

  function validateBlock() {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = "Validate";
    section.appendChild(heading);

    const button = document.createElement("button");
    button.className = "admin-button";
    button.textContent = "Validate now";
    section.appendChild(button);

    const result = document.createElement("div");
    section.appendChild(result);

    button.addEventListener("click", async () => {
      button.disabled = true;
      result.textContent = "Validating…";
      try {
        const data = await rpc("validate-file", { fileId: id });
        state.errors = data.errors ?? [];
        refreshRepair();
        result.replaceChildren();
        if (state.errors.length === 0) {
          result.textContent = "No validation errors found.";
        } else {
          const note = document.createElement("p");
          note.className = "admin-count";
          note.textContent =
            `${state.errors.length} error` + (state.errors.length === 1 ? "" : "s") + " found.";
          result.appendChild(note);
          const rows = state.errors.map((error) => ({
            ...error,
            shapeId: error.shapeId ?? "—",
            pageId: error.pageId ?? "—",
          }));
          result.appendChild(renderTable(ERROR_COLUMNS, rows));
        }
      } catch {
        result.textContent = "Could not validate the file.";
        showToast("Could not validate the file.", "error");
      } finally {
        button.disabled = false;
      }
    });
    return section;
  }

  function repairBlock() {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = "Repair";
    section.appendChild(heading);

    const hint = document.createElement("p");
    hint.className = "admin-count";
    hint.textContent =
      "Validate first, then type the error count to confirm. Repairing writes the file; a snapshot is kept by default.";
    section.appendChild(hint);

    const snapshotLabel = document.createElement("label");
    const snapshot = document.createElement("input");
    snapshot.type = "checkbox";
    snapshot.checked = true;
    snapshotLabel.appendChild(snapshot);
    snapshotLabel.append(" Keep a repair snapshot");
    section.appendChild(snapshotLabel);

    const confirmWrap = document.createElement("div");
    confirmWrap.className = "admin-confirm";

    const input = document.createElement("input");
    input.className = "admin-input";
    input.type = "text";
    input.inputMode = "numeric";
    input.setAttribute("aria-label", "Type the error count to confirm repair");
    confirmWrap.appendChild(input);

    const confirm = document.createElement("button");
    confirm.className = "admin-button admin-button-danger";
    confirm.textContent = "Repair";
    confirmWrap.appendChild(confirm);
    section.appendChild(confirmWrap);

    const result = document.createElement("div");
    section.appendChild(result);

    function expected() {
      return state.errors === null ? null : state.errors.length;
    }

    function refresh() {
      const count = expected();
      if (count === null) {
        confirm.disabled = true;
        return;
      }
      confirm.textContent = count > 0 ? `Repair ${count}` : "Repair (nothing to fix)";
      confirm.disabled = input.value.trim() !== String(count) || count === 0;
    }

    input.addEventListener("input", refresh);
    refresh();
    refreshRepair = refresh;

    confirm.addEventListener("click", async () => {
      confirm.disabled = true;
      result.textContent = "Repairing…";
      try {
        const params = { fileId: id };
        if (!snapshot.checked) {
          params.skipSnapshot = true;
        }
        const data = await rpc("repair-file", params);
        state.errors = data.errors ?? [];
        input.value = "";
        refresh();
        result.textContent =
          `Applied ${data.changes} change` + (data.changes === 1 ? "" : "s") +
          (state.errors.length === 0 ? "; no errors left." : `; ${state.errors.length} errors left.`) +
          (data.snapshotTaken ? " Snapshot kept." : "");
        showToast("Repair finished.");
      } catch {
        result.textContent = "Could not repair the file.";
        showToast("Could not repair the file.", "error");
        refresh();
      }
    });
    return section;
  }

  function exportBlock() {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = "Export";
    section.appendChild(heading);

    const libsLabel = document.createElement("label");
    const libs = document.createElement("input");
    libs.type = "checkbox";
    libsLabel.appendChild(libs);
    libsLabel.append(" Include libraries");
    section.appendChild(libsLabel);

    const embedLabel = document.createElement("label");
    const embed = document.createElement("input");
    embed.type = "checkbox";
    embedLabel.appendChild(embed);
    embedLabel.append(" Embed assets");
    section.appendChild(embedLabel);

    const buttons = document.createElement("div");
    buttons.className = "admin-confirm";

    const download = document.createElement("a");
    download.className = "admin-button";
    download.textContent = "Download";
    buttons.appendChild(download);

    const clone = document.createElement("button");
    clone.className = "admin-button admin-button-ghost";
    clone.textContent = "Clone into my project";
    buttons.appendChild(clone);
    section.appendChild(buttons);

    function exportUrl(extra = {}) {
      const url = transferUrl("file-export");
      url.searchParams.set("file-ids", id);
      if (libs.checked) {
        url.searchParams.set("includelibs", "true");
      }
      if (embed.checked) {
        url.searchParams.set("embedassets", "true");
      }
      for (const [key, value] of Object.entries(extra)) {
        url.searchParams.set(key, value);
      }
      return url.toString();
    }

    function refresh() {
      download.href = exportUrl();
    }
    libs.addEventListener("change", refresh);
    embed.addEventListener("change", refresh);
    refresh();

    clone.addEventListener("click", async () => {
      clone.disabled = true;
      try {
        const response = await fetch(exportUrl({ clone: "true" }), { credentials: "same-origin" });
        if (!response.ok) {
          throw new Error("clone failed");
        }
        showToast("Cloned into your project.");
      } catch {
        showToast("Could not clone the file.", "error");
      } finally {
        clone.disabled = false;
      }
    });
    return section;
  }
}
