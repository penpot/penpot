// lcov parsing shared by the coverage scripts.

const COUNTERS = {
  LF: "lines",
  LH: "lines",
  FNF: "functions",
  FNH: "functions",
  BRF: "branches",
  BRH: "branches",
};

export function emptyTotals() {
  return {
    files: 0,
    lines: { covered: 0, total: 0 },
    functions: { covered: 0, total: 0 },
    branches: { covered: 0, total: 0 },
  };
}

// One record per source file: `{path, lines, functions, branches}`, each
// metric as `{covered, total}`.
export function parseLcov(text) {
  const records = [];
  let current = null;
  for (const line of text.split("\n")) {
    const sep = line.indexOf(":");
    const tag = sep === -1 ? line.trim() : line.slice(0, sep);
    const value = line.slice(sep + 1).trim();
    if (tag === "SF") {
      current = { path: value, ...emptyTotals() };
    } else if (tag === "end_of_record" && current) {
      records.push(current);
      current = null;
    } else if (current && tag in COUNTERS) {
      const key = tag.endsWith("F") ? "total" : "covered";
      current[COUNTERS[tag]][key] = Number(value);
    }
  }
  return records;
}

export function addTotals(totals, record) {
  totals.files += 1;
  for (const key of ["lines", "functions", "branches"]) {
    totals[key].covered += record[key].covered;
    totals[key].total += record[key].total;
  }
}
