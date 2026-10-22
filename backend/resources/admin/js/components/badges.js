// Shared status badges for the admin panel: fixed class maps with
// values painted as text, never the raw value as a class. These used
// to live in `pages/users.js`, which coupled every list (and the user
// detail) to the users page; they live here now.

const STATUS_BADGES = {
  deleted: "admin-badge-status-deleted",
  blocked: "admin-badge-status-blocked",
  inactive: "admin-badge-status-inactive",
  demo: "admin-badge-status-demo",
  active: "admin-badge-status-active",
};

const DEFAULT_BADGES = {
  yes: "admin-badge-status-active",
  no: "admin-badge-status-inactive",
};

// Derive the badge key from a profile row.
export function statusOf(item) {
  if (item.deletedAt) {
    return "deleted";
  }
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

export function statusCell(status) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (STATUS_BADGES[status] || STATUS_BADGES.active);
  badge.textContent = status;
  return badge;
}

// Yes/no badge for the team "Default" column.
export function defaultCell(value) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (DEFAULT_BADGES[value] || DEFAULT_BADGES.no);
  badge.textContent = value;
  return badge;
}

const JOB_STATUS_BADGES = {
  new: "admin-badge-status-demo",
  scheduled: "admin-badge-status-demo",
  retry: "admin-badge-status-demo",
  running: "admin-badge-status-active",
  completed: "admin-badge-status-inactive",
  failed: "admin-badge-status-blocked",
  cancelled: "admin-badge-status-deleted",
  aborted: "admin-badge-status-deleted",
};

const JOB_KIND_BADGES = {
  system: "admin-badge-source-legacy",
  user: "admin-badge-source-logging",
};

// Badge for a job status or kind: fixed class maps, never the raw
// value as a class. Unknown values fall back to a neutral badge.
export function jobStatusCell(status) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (JOB_STATUS_BADGES[status] || "admin-badge-status-inactive");
  badge.textContent = status;
  return badge;
}

export function jobKindCell(kind) {
  const badge = document.createElement("span");
  badge.className = "admin-badge " + (JOB_KIND_BADGES[kind] || "admin-badge-source-legacy");
  badge.textContent = kind;
  return badge;
}
