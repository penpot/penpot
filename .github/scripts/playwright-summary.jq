def specs: [.. | objects | select(has("tests") and has("file"))];
def dur: [.tests[].results[]?.duration // 0] | add;

specs as $s
| ($s | map(select(any(.tests[]; .status == "unexpected")))) as $failed
| ($s | map(select(any(.tests[]; .status == "flaky"))))      as $flaky
| ($s | map(select(any(.tests[]; .status == "skipped"))))    as $skipped
| ($s | length) as $total
| ($s | map(dur) | add // 0 | . / 1000 | floor) as $cpu
| (if ($failed | length) > 0 then "❌"
   elif ($flaky | length) > 0 then "⚠️"
   else "✅" end) as $icon

| "## \($icon) Integration tests\n\n"
+ "| Total | Passed | Flaky | Failed | Skipped | Test time |\n"
+ "|---|---|---|---|---|---|\n"
+ "| \($total) | \($total - ($failed|length) - ($flaky|length) - ($skipped|length)) "
+ "| \($flaky|length) | \($failed|length) | \($skipped|length) | \($cpu / 60 | floor)m |\n"

+ (if ($failed | length) > 0 then
     "\n### Failed\n\n"
     + ($failed | map("- `\(.file):\(.line)` — \(.title)") | join("\n")) + "\n"
   else "" end)

+ (if ($flaky | length) > 0 then
     "\n### Flaky (passed on retry)\n\n"
     + ($flaky
        | map({ t: "`\(.file):\(.line)` — \(.title)",
                r: ([.tests[].results[]? | select(.status == "failed")] | length) })
        | sort_by(-.r)
        | map("- \(.t) _(\(.r) \(if .r == 1 then "retry" else "retries" end))_")
        | join("\n")) + "\n"
   else "" end)

+ (if $total > 0 then
     "\n<details><summary>Slowest specs</summary>\n\n"
     + ($s | map({ t: "`\(.file)` — \(.title)", d: (dur / 1000 | floor) })
          | sort_by(-.d) | .[0:5]
          | map("- \(.t) — \(.d)s") | join("\n"))
     + "\n\n</details>\n"
   else "" end)
