// Job detail: metadata, owner, bounded params, error/result and
// the append-only event history, plus Cancel for live jobs.
// The job resolves by id through `get-job`; a GC-swept id shows
// "not found" instead of failing. Cancelling a running job stops
// it at its next heartbeat; a job the runner just finished answers
// as a no-op ("already picked up, reload"). Every value is painted
// as text.

import { rpc, errorHint } from "../api.js";
import { jobKindCell, jobStatusCell } from "../components/badges.js";
import { formatDate } from "../components/date.js";
import { renderHeader, paintDetailTitle } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { backUrl } from "../url.js";

const CANCELLABLE = ["new", "scheduled", "retry", "running"];

const EVENT_COLUMNS = [
  { key: "createdAt", label: "Date", class: "admin-cell-date" },
  { key: "kind", label: "Kind", class: "admin-cell-kind" },
  { key: "detail", label: "Detail", class: "admin-cell-left" },
];

export function jobDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Job");
  root.appendChild(header);

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.title = "Back to the jobs list.";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=jobs")));
  bar.appendChild(back);
  root.appendChild(bar);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  let clearCancelAction = null;

  load();

  async function load() {
    let job;
    try {
      job = await rpc("get-job", { id });
    } catch (err) {
      body.replaceChildren();
      if (clearCancelAction) {
        clearCancelAction();
        clearCancelAction = null;
      }
      if (err.status === 404) {
        body.textContent = "Job not found.";
      } else {
        body.textContent = "Could not load the job.";
        showToast("Could not load the job.", "error");
      }
      return;
    }
    paintDetailTitle(header, {
      section: "Job",
      query: backUrl("?screen=jobs"),
      name: job.name,
      onNavigate,
    });
    body.replaceChildren();
    body.appendChild(infoBlock(job, onNavigate));
    body.appendChild(paramsBlock(job));
    body.appendChild(eventsBlock(job));
    if (clearCancelAction) {
      clearCancelAction();
      clearCancelAction = null;
    }
    if (CANCELLABLE.includes(job.status)) {
      clearCancelAction = paintCancelAction(bar, { job, onCancelled: () => load() });
    }
  }

  function infoRow(list, label, value) {
    const term = document.createElement("dt");
    term.textContent = label;
    const desc = document.createElement("dd");
    desc.textContent = value;
    list.appendChild(term);
    list.appendChild(desc);
  }

  function infoBlock(job, onNavigate) {
    const section = document.createElement("section");
    const title = document.createElement("h2");
    title.textContent = "Job";
    section.appendChild(title);

    const badges = document.createElement("p");
    badges.appendChild(jobStatusCell(job.status));
    badges.appendChild(document.createTextNode(" "));
    badges.appendChild(jobKindCell(job.kind));
    section.appendChild(badges);

    const list = document.createElement("dl");
    list.className = "admin-detail";
    infoRow(list, "Id", job.id);
    infoRow(list, "Name", job.name);
    infoRow(list, "Queue", job.queue);
    if (job.label) {
      infoRow(list, "Label", job.label);
    }
    infoRow(list, "Tenant", job.tenant);
    infoRow(list, "Priority", String(job.priority));
    infoRow(list, "Retries", `${job.retryNum}/${job.maxRetries}`);
    infoRow(list, "Scheduled", formatDate(job.scheduledAt));
    infoRow(list, "Created", formatDate(job.createdAt));
    infoRow(list, "Modified", formatDate(job.modifiedAt));
    if (job.startedAt) {
      infoRow(list, "Started", formatDate(job.startedAt));
    }
    if (job.completedAt) {
      infoRow(list, "Completed", formatDate(job.completedAt));
    }
    if (job.expiresAt) {
      infoRow(list, "Expires", formatDate(job.expiresAt));
    }
    section.appendChild(list);

    const ownerTitle = document.createElement("h2");
    ownerTitle.textContent = "Owner";
    section.appendChild(ownerTitle);
    if (job.profileId) {
      const owner = document.createElement("p");
      const link = document.createElement("a");
      link.textContent = job.ownerEmail ?? job.profileId;
      link.title = "Open the owner profile.";
      link.href = "?screen=user&id=" + encodeURIComponent(job.profileId);
      link.addEventListener("click", (event) => {
        event.preventDefault();
        onNavigate("?screen=user&id=" + encodeURIComponent(job.profileId));
      });
      owner.appendChild(link);
      if (job.ownerFullname) {
        owner.appendChild(document.createTextNode(` (${job.ownerFullname})`));
      }
      section.appendChild(owner);
    } else {
      const none = document.createElement("p");
      none.className = "admin-count";
      none.textContent = "System job — no profile.";
      section.appendChild(none);
    }

    if (job.error) {
      const errorTitle = document.createElement("h2");
      errorTitle.textContent = "Error";
      section.appendChild(errorTitle);
      section.appendChild(codeBlock(JSON.stringify(job.error, null, 2)));
    }
    if (job.result) {
      const resultTitle = document.createElement("h2");
      resultTitle.textContent = "Result";
      section.appendChild(resultTitle);
      section.appendChild(codeBlock(JSON.stringify(job.result, null, 2)));
    }
    return section;
  }

  function paramsBlock(job) {
    const section = document.createElement("section");
    const title = document.createElement("h2");
    title.textContent = "Params";
    section.appendChild(title);
    const note = document.createElement("p");
    note.className = "admin-count";
    note.textContent = "Bounded preview: depth and length limited.";
    section.appendChild(note);
    section.appendChild(codeBlock(job.paramsPretty));
    return section;
  }

  function codeBlock(text) {
    const pre = document.createElement("pre");
    pre.className = "admin-block-body";
    pre.textContent = text;
    return pre;
  }

  function eventsBlock(job) {
    const section = document.createElement("section");
    const title = document.createElement("h2");
    title.textContent = `Events (${job.events.length})`;
    section.appendChild(title);
    if (job.events.length === 0) {
      const none = document.createElement("p");
      none.className = "admin-count";
      none.textContent = "No events recorded yet.";
      section.appendChild(none);
      return section;
    }
    const rows = job.events.map((event) => ({
      createdAt: formatDate(event.createdAt),
      kind: event.kind,
      detail: JSON.stringify(event.payload),
    }));
    section.appendChild(renderTable(EVENT_COLUMNS, rows));
    return section;
  }

  function paintCancelAction(bar, { job, onCancelled }) {
    const cancel = document.createElement("button");
    cancel.className = "admin-button admin-button-danger";
    cancel.textContent = "Cancel";
    cancel.title = "Cancel this job. A running job stops at its next heartbeat.";
    cancel.addEventListener("click", async () => {
      if (!window.confirm(
        `Cancel ${job.name}? A running job stops at its next heartbeat.`
      )) {
        return;
      }
      cancel.disabled = true;
      try {
        const out = await rpc("cancel-job", { id: job.id });
        if (out.cancelled) {
          showToast("Job cancelled.");
        } else {
          showToast("The job already finished. Reload to see it.", "error");
        }
        onCancelled();
      } catch (err) {
        showToast(errorHint(err) ?? "Could not cancel the job.", "error");
        cancel.disabled = false;
      }
    });
    bar.appendChild(cancel);

    return () => {
      cancel.remove();
    };
  }
}
