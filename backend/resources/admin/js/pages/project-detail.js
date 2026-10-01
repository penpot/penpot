// Project detail: metadata plus a jump to its files.
// The project resolves by id through `get-project`, regardless of
// its own or its team's deleted state: the admin must reach stray
// objects the lists hide. (Lists keep hiding children of deleted
// parents.) The "View files" button opens the files list filtered
// by this project; the team name links back to the team page.
// Every value is painted as text. An unknown id shows "not found"
// instead of failing.

import { rpc } from "../api.js";
import { formatDate } from "../components/date.js";
import { renderHeader, paintDetailTitle } from "../components/header.js";
import { deletedNotice, restoreBlock } from "../components/restore.js";
import { paintDeleteAction } from "../components/delete.js";
import { showToast } from "../components/toast.js";
import { backUrl } from "../url.js";

export function projectDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Project");
  root.appendChild(header);

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=projects")));
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

  let clearDeleteAction = null;

  load();

  async function load() {
    let project;
    try {
      project = await rpc("get-project", { id });
    } catch (err) {
      body.replaceChildren();
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      if (err.status === 404) {
        body.textContent = "Project not found.";
      } else {
        body.textContent = "Could not load the project.";
        showToast("Could not load the project.", "error");
      }
      return;
    }
    paintDetailTitle(header, {
      section: "Project",
      query: backUrl("?screen=projects"),
      name: project.name,
      onNavigate,
    });
    body.replaceChildren();
    body.appendChild(infoBlock(project));
    if (project.deletedAt) {
      body.appendChild(deletedNotice("This project was marked for deletion."));
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      // NOTE: default projects come back with their owner profile,
      // so no Restore button is painted for them.
      if (project.isDefault) {
        const hint = document.createElement("p");
        hint.className = "admin-count";
        hint.textContent =
          "This is a default project. Restore its owner profile to bring it back.";
        body.appendChild(hint);
      } else {
        body.appendChild(restoreBlock({
          command: "restore-project",
          id,
          label: "this project",
          scope: "with its files and team",
          onRestored: () => load(),
        }));
      }
    } else {
      if (clearDeleteAction) {
        clearDeleteAction();
      }
      // NOTE: default projects cannot be deleted (the backend
      // refuses them too), so no Delete button is painted for them.
      if (!project.isDefault) {
        clearDeleteAction = paintDeleteAction(bar, {
          command: "delete-project",
          id,
          label: "this project",
          name: project.name,
          kind: "Project",
          onDeleted: () => load(),
        });
      } else {
        clearDeleteAction = null;
      }
    }
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
