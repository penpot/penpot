// File detail: metadata plus validate, repair, and export.
// The file resolves by id through `get-file`, regardless of its
// own or its parents' deleted state: the admin must reach stray
// objects the lists hide. (Lists keep hiding children of deleted
// parents.) Validating paints the error table; repairing asks to
// type the error count first (like the bulk user delete) and
// snapshots by default; exporting is a plain download link with
// the session cookie. Every value is painted as text. An unknown
// id shows "not found" instead of failing.

import { rpc, methodUrl } from "../api.js";
import { renderHeader, paintDetailTitle } from "../components/header.js";
import { deletedNotice, restoreBlock } from "../components/restore.js";
import { paintDeleteAction } from "../components/delete.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { backUrl } from "../url.js";

const ERROR_COLUMNS = [
  { key: "code", label: "Code", class: "admin-cell-name" },
  { key: "hint", label: "Hint", class: "admin-cell-left" },
  { key: "shapeId", label: "Shape", class: "admin-cell-left" },
  { key: "pageId", label: "Page", class: "admin-cell-left" },
];

export function fileDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("File");
  root.appendChild(header);

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=files")));
  bar.appendChild(back);
  root.appendChild(bar);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  const state = { file: null, errors: null };
  let refreshRepair = () => {};
  let clearDeleteAction = null;

  load();

  async function load() {
    let file;
    try {
      file = await rpc("get-file", { id });
    } catch (err) {
      body.replaceChildren();
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      if (err.status === 404) {
        body.textContent = "File not found.";
      } else {
        body.textContent = "Could not load the file.";
        showToast("Could not load the file.", "error");
      }
      return;
    }
    state.file = file;
    paintDetailTitle(header, {
      section: "File",
      query: backUrl("?screen=files"),
      name: file.name,
      onNavigate,
    });
    body.replaceChildren();
    body.appendChild(infoBlock(file));
    body.appendChild(validateBlock());
    body.appendChild(repairBlock());
    body.appendChild(exportBlock());
    if (file.deletedAt) {
      body.appendChild(deletedNotice("This file was marked for deletion."));
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      body.appendChild(restoreBlock({
        command: "restore-file",
        id,
        label: "this file",
        scope: "with its project and team",
        onRestored: () => load(),
      }));
    } else {
      if (clearDeleteAction) {
        clearDeleteAction();
      }
      clearDeleteAction = paintDeleteAction(bar, {
        command: "delete-file",
        id,
        label: "this file",
        name: file.name,
        kind: "File",
        onDeleted: () => load(),
      });
    }
  }

  function infoBlock(file) {
    const list = document.createElement("dl");
    list.className = "admin-detail";
    const idTerm = document.createElement("dt");
    idTerm.textContent = "Id";
    const idDesc = document.createElement("dd");
    idDesc.textContent = String(file.id ?? "—");
    list.appendChild(idTerm);
    list.appendChild(idDesc);

    const projectTerm = document.createElement("dt");
    projectTerm.textContent = "Project";
    const projectDesc = document.createElement("dd");
    const projectLink = document.createElement("a");
    projectLink.textContent = String(file.projectName ?? file.projectId ?? "—");
    projectLink.href = "?screen=project&id=" + encodeURIComponent(file.projectId);
    projectLink.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate("?screen=project&id=" + encodeURIComponent(file.projectId));
    });
    projectDesc.appendChild(projectLink);
    list.appendChild(projectTerm);
    list.appendChild(projectDesc);

    const teamTerm = document.createElement("dt");
    teamTerm.textContent = "Team";
    const teamDesc = document.createElement("dd");
    const teamLink = document.createElement("a");
    teamLink.textContent = String(file.teamName ?? file.teamId ?? "—");
    teamLink.href = "?screen=team&id=" + encodeURIComponent(file.teamId);
    teamLink.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate("?screen=team&id=" + encodeURIComponent(file.teamId));
    });
    teamDesc.appendChild(teamLink);
    list.appendChild(teamTerm);
    list.appendChild(teamDesc);
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
    result.className = "admin-result";
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
      "Repairing writes the file; a snapshot is kept by default. Dangerous actions ask for confirmation first.";
    section.appendChild(hint);

    const snapshotLabel = document.createElement("label");
    snapshotLabel.className = "admin-check";
    const snapshot = document.createElement("input");
    snapshot.type = "checkbox";
    snapshot.checked = true;
    snapshotLabel.appendChild(snapshot);
    snapshotLabel.append(" Keep a repair snapshot");
    section.appendChild(snapshotLabel);

    const confirmWrap = document.createElement("div");
    confirmWrap.className = "admin-confirm";

    const confirm = document.createElement("button");
    confirm.className = "admin-button admin-button-danger";
    confirm.textContent = "Repair";
    confirm.title = "Repair the file, fixing the validated errors.";
    confirmWrap.appendChild(confirm);
    section.appendChild(confirmWrap);

    const result = document.createElement("div");
    result.className = "admin-result";
    section.appendChild(result);

    function expected() {
      return state.errors === null ? null : state.errors.length;
    }

    function refresh() {
      const count = expected();
      if (count === null) {
        confirm.textContent = "Repair";
        confirm.title = "Validate first, then repair.";
        confirm.disabled = true;
        return;
      }
      confirm.textContent = count > 0 ? `Repair ${count}` : "Repair (nothing to fix)";
      confirm.title = count > 0
        ? `Repair ${count} errors. A snapshot is kept.`
        : "Nothing to fix.";
      confirm.disabled = count === 0;
    }

    refresh();
    refreshRepair = refresh;

    confirm.addEventListener("click", async () => {
      const count = expected() ?? 0;
      if (!window.confirm(`Repair ${count} errors? A snapshot is kept by default.`)) {
        return;
      }
      confirm.disabled = true;
      result.textContent = "Repairing…";
      try {
        const params = { fileId: id };
        if (!snapshot.checked) {
          params.skipSnapshot = true;
        }
        const data = await rpc("repair-file", params);
        state.errors = data.errors ?? [];
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
    libsLabel.className = "admin-check";
    const libs = document.createElement("input");
    libs.type = "checkbox";
    libsLabel.appendChild(libs);
    libsLabel.append(" Include libraries");
    section.appendChild(libsLabel);

    const embedLabel = document.createElement("label");
    embedLabel.className = "admin-check";
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

    function exportBody(extra = {}) {
      return JSON.stringify({
        fileIds: [id],
        includelibs: libs.checked,
        embedassets: embed.checked,
        ...extra,
      });
    }

    async function fetchExport(extra = {}) {
      const response = await fetch(methodUrl("export-files"), {
        method: "POST",
        credentials: "same-origin",
        headers: { "content-type": "application/json" },
        body: exportBody(extra),
      });
      if (!response.ok) {
        throw new Error("export failed");
      }
      return response;
    }

    function downloadName(response) {
      const disposition = response.headers.get("content-disposition") ?? "";
      const filename = disposition.split("filename=")[1];
      return (filename ?? id + ".penpot").trim();
    }

    download.addEventListener("click", async (event) => {
      event.preventDefault();
      try {
        const response = await fetchExport();
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = downloadName(response);
        anchor.click();
        URL.revokeObjectURL(url);
      } catch {
        showToast("Could not download the file.", "error");
      }
    });

    clone.addEventListener("click", async () => {
      clone.disabled = true;
      try {
        await fetchExport({ clone: true });
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
