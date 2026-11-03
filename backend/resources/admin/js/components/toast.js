// Toasts: short-lived notifications. The message is painted as text,
// and only "info" and "error" types exist (anything else is info).

const DISPLAY_MS = 4000;

export function showToast(message, type = "info") {
  const toast = document.createElement("div");
  toast.className = "admin-toast admin-toast-" + (type === "error" ? "error" : "info");
  toast.textContent = message;
  document.body.appendChild(toast);

  window.setTimeout(() => toast.remove(), DISPLAY_MS);
}
