// Project detail: metadata plus a jump to its files.
// The project is located through the exact-id search, so no extra
// detail command is needed. The "View files" button opens the
// files list filtered by this project; the team name links back to
// the team page. Every value is painted as text. An unknown id
// shows "not found" instead of failing.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

export function projectDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Project");
  root.appendChild(header);

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("?screen=projects"));
  bar.appendChild(back);

  const filesLink = document.createElement("button");
  filesLink.className = "admin-button admin-button-ghost";
  filesLink.textContent = "View files";
  filesLink.addEventListener("click", () =>
    onNavigate("?screen=files&projectId=" + encodeURIComponent(id)));
  bar.appendChild(filesLink);
  root.appendChild(bar);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  load();

  async function load() {
    let found;
    try {
      found = await rpc("get-projects", { search: id });
    } catch {
      body.replaceChildren();
      body.textContent = "Could not load the project.";
      showToast("Could not load the project.", "error");
      return;
    }
    const project = (found.items ?? []).find((item) => item.id === id) ?? null;
    if (project === null) {
      body.replaceChildren();
      body.textContent = "Project not found.";
      return;
    }
    header.querySelector("h1").textContent = project.name;
    body.replaceChildren();
    body.appendChild(infoBlock(project));
  }

  function infoBlock(project) {
    const list = document.createElement("dl");
    list.className = "admin-detail";
    const rows = [
      ["id", "Id", String(project.id ?? "—"), null],
      ["createdAt", "Created", formatDate(project.createdAt), null],
      ["modifiedAt", "Modified", formatDate(project.modifiedAt), null],
      ["totalFiles", "Files", String(project.totalFiles ?? 0), null],
    ];
    for (const [, label, text] of rows) {
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = text;
      list.appendChild(term);
      list.appendChild(desc);
    }
    if (project.isDefault) {
      const term = document.createElement("dt");
      term.textContent = "Default";
      const desc = document.createElement("dd");
      desc.textContent = "This is the default project of its team.";
      list.appendChild(term);
      list.appendChild(desc);
    }
    const teamTerm = document.createElement("dt");
    teamTerm.textContent = "Team";
    const teamDesc = document.createElement("dd");
    const teamLink = document.createElement("a");
    teamLink.textContent = String(project.teamName ?? project.teamId ?? "—");
    teamLink.href = "?screen=team&id=" + encodeURIComponent(project.teamId);
    teamLink.addEventListener("click", (event) => {
      event.preventDefault();
      onNavigate("?screen=team&id=" + encodeURIComponent(project.teamId));
    });
    teamDesc.appendChild(teamLink);
    list.appendChild(teamTerm);
    list.appendChild(teamDesc);
    return list;
  }
}
