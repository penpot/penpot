# Project anomalies helper

- For usage, anomaly definitions and authorized fixes, use the `find-project-anomalies` skill.
- `scripts/project-anomalies.py` fetches milestone issues and PRs through `scripts/gh.py` and resolves outside references in batches. Keep GitHub queries out of per-item loops.
- `find_anomalies` and `render_report` operate on pre-resolved data without network calls. Merge resolved outsiders into the lookup maps before detection; missing numbers are skipped, not flagged.
