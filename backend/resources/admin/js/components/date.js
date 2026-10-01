// Shared date formatting for the admin panel: every date column
// in every list and detail uses this, so dates always read
// `YYYY/MM/DD HH:MM:SS AM/PM` (local time, 12-hour clock).
// Unparseable input echoes back raw so a bad value is visible
// instead of crashing. The caller paints the result as text.

function pad2(value) {
  return String(value).padStart(2, "0");
}

export function formatDate(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return String(iso ?? "");
  }
  const hours = date.getHours();
  const meridiem = hours < 12 ? "AM" : "PM";
  const twelve = hours % 12 || 12;
  return (
    `${date.getFullYear()}/${pad2(date.getMonth() + 1)}/${pad2(date.getDate())} ` +
    `${pad2(twelve)}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())} ${meridiem}`
  );
}
