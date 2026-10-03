// Virtual clock: show the per-profile time offset and set or
// reset it. The offset only affects the current profile's session
// and is a test aid, never a production control (production
// refuses changes). Every value is painted as text.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

function formatOffset(millis) {
  if (millis === null || millis === undefined) {
    return "none";
  }
  const totalSeconds = Math.round(millis / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const parts = [];
  if (hours) {
    parts.push(`${hours}h`);
  }
  if (minutes) {
    parts.push(`${minutes}m`);
  }
  if (!parts.length || seconds) {
    parts.push(`${seconds}s`);
  }
  return parts.join("");
}

export function virtualClockPage(root) {
  root.appendChild(renderHeader("Virtual clock"));

  const hint = document.createElement("p");
  hint.className = "admin-count";
  hint.textContent =
    "Shift this session's clock for testing (for example 1h, 90m, 2h30m). " +
    "Reset returns to real time. Production refuses changes.";
  root.appendChild(hint);

  const status = document.createElement("p");
  status.className = "admin-count admin-count-spaced";
  status.textContent = "Loading…";
  root.appendChild(status);

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const offsetInput = document.createElement("input");
  offsetInput.className = "admin-input";
  offsetInput.type = "text";
  offsetInput.placeholder = "Offset, e.g. 1h";
  offsetInput.setAttribute("aria-label", "Clock offset");
  offsetInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      setButton.click();
    }
  });
  controls.appendChild(offsetInput);

  const setButton = document.createElement("button");
  setButton.className = "admin-button";
  setButton.textContent = "Set offset";
  setButton.addEventListener("click", async () => {
    const offset = offsetInput.value.trim();
    if (!offset) {
      showToast("Type an offset first.", "error");
      return;
    }
    if (!window.confirm(`Shift this session's clock by ${offset}?`)) {
      return;
    }
    setButton.disabled = true;
    try {
      const data = await rpc("set-virtual-clock", { offset });
      paint(data);
      showToast("Clock offset set.");
    } catch {
      showToast("Could not set the clock offset.", "error");
    } finally {
      setButton.disabled = false;
    }
  });
  controls.appendChild(setButton);

  const resetButton = document.createElement("button");
  resetButton.className = "admin-button admin-button-ghost";
  resetButton.textContent = "Reset";
  resetButton.addEventListener("click", async () => {
    resetButton.disabled = true;
    try {
      const data = await rpc("set-virtual-clock", { reset: true });
      offsetInput.value = "";
      paint(data);
      showToast("Clock reset to real time.");
    } catch {
      showToast("Could not reset the clock.", "error");
    } finally {
      resetButton.disabled = false;
    }
  });
  controls.appendChild(resetButton);
  root.appendChild(controls);

  function paint(data) {
    status.textContent =
      `Offset: ${formatOffset(data.offsetMillis)} · ` +
      `Time: ${formatDate(data.now)} · ` +
      `Clock: ${data.clock}`;
  }

  async function load() {
    try {
      paint(await rpc("get-virtual-clock"));
    } catch {
      status.textContent = "Could not load the clock.";
    }
  }

  load();
}
