"""Compare two render-budget-perf runs.

    python3 playwright/scripts/render-budget-compare.py \
        perf-traces/render-budget-before.json perf-traces/render-budget-after.json

Reads the JSON written by playwright/ui/render-wasm-specs/render-budget-perf.spec.js.

Two kinds of numbers, in this order of trust:

COUNTERS are exact repaint counts read out of WASM (tiles painted, shape paints,
walker visits, paragraph builds, composited pixels...). On identical gestures
they do not drift, so any delta is a real algorithmic change. This is the section
to read when judging a render-pipeline change.

SYNC / rAF rows are `_render` wall times: SYNC ran on the input/timer path
(pointerup, debounce), rAF inside a frame callback. Useful for latency, but noisy
across machines and thermal states — do not conclude anything from a <15% move.
"""

import json
import sys

KEYS = ("n", "mean", "p50", "p95", "max", "total")
# n is a count, the rest are milliseconds.
LOWER_IS_BETTER = ("mean", "p50", "p95", "max", "total")

# Counters worth printing, in reading order. Everything else in the file is
# still there for ad-hoc digging.
COUNTER_ROWS = (
    ("tiles_painted", "tiles actually walked + composited"),
    ("tiles_cache_hit", "tiles served from the texture cache"),
    ("tiles_invalidated", "single-tile evictions"),
    ("tile_cache_wipes", "whole-cache wipes"),
    ("tiles_discarded_inflight", "partial tiles thrown away by a restart"),
    ("shape_paints", "render_shape calls"),
    ("shape_paints_direct", "of those, straight into the tile"),
    ("walker_visits", "tree nodes visited"),
    ("walker_culled", "visits that painted nothing"),
    ("surface_stack_composites", "full surface-stack composites"),
    ("surface_stack_draw_px", "pixels moved by those composites"),
    ("surface_stack_clear_px", "pixels cleared after them"),
    ("paragraph_builds", "paragraph builder groups built"),
    ("text_layouts", "text build + Skia layout runs"),
    ("doc_atlas_writes", "Current -> DocAtlas blits"),
    ("tile_atlas_writes", "Current -> tile atlas blits"),
    ("cache_surface_writes", "Current -> legacy Cache blits (dead)"),
    ("tile_atlas_snapshots", "full tile-atlas snapshots"),
    ("tile_atlas_snapshot_px", "pixels in those snapshots"),
    ("render_loop_starts", "renders restarted from tile zero"),
    ("render_loop_continues", "renders resumed"),
    ("partial_yields", "budget yields"),
    ("frame_presents", "frames presented"),
)

# Direction of "good" per counter. Default is lower-is-better (less work).
# `higher`: more of this means work was avoided. `neutral`: the number is
# diagnostic, not a score — a move is worth looking at, not celebrating.
COUNTER_DIRECTION = {
    "tiles_cache_hit": "higher",
    "tile_cache_hit_ratio": "higher",
    "culled_ratio": "higher",
    "walker_culled": "higher",
    "shape_paints_direct": "neutral",
    "layered_paint_ratio": "neutral",
    "empty_tile_ratio": "neutral",
    "frame_presents": "neutral",
    "render_loop_starts": "neutral",
    "render_loop_continues": "neutral",
    "partial_yields": "neutral",
    "doc_atlas_writes": "neutral",
    "tile_atlas_writes": "neutral",
    "tile_atlas_snapshots": "neutral",
    "tiles_painted_per_present": "neutral",
    # Should hold steady across a change that only removes redundant work; if it
    # moves, something is being skipped or repainted that was not before.
    "tiles_painted": "neutral",
}

RATIO_ROWS = (
    ("shape_paints_per_tile", "shape paints per tile painted"),
    ("walker_visits_per_tile", "tree visits per tile painted"),
    ("culled_ratio", "share of visits that painted nothing"),
    ("layered_paint_ratio", "share of paints needing the surface stack"),
    ("composite_px_per_tile", "composite px per tile (512 tile = 262144 px)"),
    ("paragraph_builds_per_tile", "paragraph builds per tile"),
    ("text_layouts_per_tile", "text layouts per tile"),
    ("tile_cache_hit_ratio", "cache hits / (hits + repaints)"),
    ("tiles_painted_per_present", "tiles painted per presented frame"),
    ("empty_tile_ratio", "share of tiles with no shapes (>0.5 = bad gestures)"),
)


def load(path):
    with open(path) as fh:
        return json.load(fh)


def get(stat, key):
    if not stat or not stat.get("n"):
        return 0
    return stat.get(key, 0)


def pct(before, after):
    """Signed percentage change, or None when there is no baseline."""
    if before == 0:
        return None
    return (after - before) / before * 100


def num(v):
    if isinstance(v, float) and not v.is_integer():
        return f"{v:.2f}"
    if abs(v) >= 1e6:
        return f"{v / 1e6:.1f}M"
    return f"{v:g}"


def delta(before, after, key):
    b, a = get(before, key), get(after, key)
    if b == 0 and a == 0:
        return ""
    p = pct(b, a)
    if p is None:
        return f"  (new {a:g})"
    sign = "+" if p >= 0 else ""
    mark = ""
    if key in LOWER_IS_BETTER and abs(p) >= 10:
        mark = "  better" if p < 0 else "  WORSE"
    return f"  {sign}{p:.0f}%{mark}"


def row(label, before, after):
    cells = []
    for k in KEYS:
        b, a = get(before, k), get(after, k)
        cells.append(f"{b:>9g} -> {a:<9g}")
    print(f"  {label:<10}" + "".join(f"{c:<22}" for c in cells))
    print(f"  {'':<10}" + "".join(f"{delta(before, after, k):<22}" for k in KEYS))


def counter_table(title, rows, before, after, threshold):
    """Prints one exact-count table. `before`/`after` are flat name -> number."""
    if not before and not after:
        return
    if before.get("error") or after.get("error"):
        print(f"  {title}: unavailable ({before.get('error') or after.get('error')})")
        return

    print(f"  {title}")
    for key, description in rows:
        b = before.get(key, 0)
        a = after.get(key, 0)
        if b == 0 and a == 0:
            continue
        p = pct(b, a)
        direction = COUNTER_DIRECTION.get(key, "lower")
        if p is None:
            change = f"new {num(a)}"
        else:
            change = f"{'+' if p >= 0 else ''}{p:.0f}%"
            # Counts are exact: a small move is a real move, not noise. Only
            # call it out past `threshold` so the table stays readable.
            if abs(p) >= threshold:
                if direction == "neutral":
                    change += "  check"
                else:
                    improved = p < 0 if direction == "lower" else p > 0
                    change += "  better" if improved else "  WORSE"
        print(
            f"    {key:<28}{num(b):>12} -> {num(a):<12}{change:<16}{description}"
        )
    print()


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)

    a, b = load(sys.argv[1]), load(sys.argv[2])

    print(f"\nBEFORE  {a['label']:<12} rev={a['rev']:<18} {a['date']}")
    print(f"AFTER   {b['label']:<12} rev={b['rev']:<18} {b['date']}")
    print(f"file    {a['file']}")
    if a["file"] != b["file"]:
        print(f"  !! AFTER used a different file: {b['file']}")
    if a["options"] != b["options"]:
        print(f"  !! gesture options differ, runs are not comparable")
        print(f"     before {a['options']}")
        print(f"     after  {b['options']}")

    names = list(dict.fromkeys(list(a["phases"]) + list(b["phases"])))
    for name in names:
        pa = a["phases"].get(name, {})
        pb = b["phases"].get(name, {})
        sa, sb = pa.get("summary", {}), pb.get("summary", {})
        print(f"\n{'=' * 72}\n{name}\n{'=' * 72}")

        # A phase where WASM raised did less work than the gestures asked for,
        # so its counters understate. Say so before anyone reads a delta off it.
        for tag, p in (("before", pa), ("after", pb)):
            errs = p.get("wasmErrors") or []
            if errs:
                print(f"  !! [{tag}] {len(errs)} wasm error(s): {errs[0][:120]}")

        counter_table(
            "COUNTS (exact)",
            COUNTER_ROWS,
            pa.get("counters") or {},
            pb.get("counters") or {},
            threshold=5,
        )
        counter_table(
            "PER-TILE RATIOS (exact)",
            RATIO_ROWS,
            pa.get("ratios") or {},
            pb.get("ratios") or {},
            threshold=5,
        )

        print("  TIMES (noisy)")
        header = "".join(f"{k:<22}" for k in KEYS)
        print(f"  {'':<10}{header}")
        row("SYNC", sa.get("sync"), sb.get("sync"))
        row("rAF", sa.get("raf"), sb.get("raf"))

        for tag, p in (("before", sa), ("after", sb)):
            w = p.get("worstSync")
            if w:
                print(
                    f"  worst sync [{tag}] {w['ms']}ms flags={w['flags']} "
                    f"frameType={w['frame']} @{w.get('caller') or '?'}"
                )

        sync_a, sync_b = get(sa.get("sync"), "total"), get(sb.get("sync"), "total")
        raf_a, raf_b = get(sa.get("raf"), "total"), get(sb.get("raf"), "total")
        print(
            f"  total render work {sync_a + raf_a:.1f}ms -> {sync_b + raf_b:.1f}ms"
            f"   (of which off the input path: "
            f"{raf_a:.1f} -> {raf_b:.1f})"
        )

    print()


if __name__ == "__main__":
    main()
