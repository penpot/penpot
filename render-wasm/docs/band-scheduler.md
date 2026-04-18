# Band-Aware Tile Scheduler

Design notes for the tile scheduler that powers `render-wasm` when the `tile-scheduler` feature is enabled. Explains *why* shapes need to be split into bands within tiles when gather effects (glass, background blur) are present, and how the scheduler executes a band-aware schedule correctly and with minimal overhead.

---

## 1. The problem: tiles aren't the right node

A naive tile scheduler treats each tile as a single node in a dependency graph. For each tile it renders every shape that touches it, finalizes the tile to the Target surface, then moves on. That works until a gather effect appears.

A **gather** effect (glass / background blur) samples a region of the already-composited Target surface as its backdrop. If that region spans tile boundaries, the gather's tile must be rendered **after** the other tiles it samples — otherwise the backdrop is missing content.

That requirement is expressed as dependency edges between tiles. With a single gather covering multiple tiles, things break:

```
                         +────+────+
                         │ T1 │ T2 │     G (gather) and X (below G) both
                         │ XG │ XG │     live in all four tiles.
                         +────+────+
                         │ T3 │ T4 │
                         │ XG │ XG │
                         +────+────+

   T1 → T2   (T1's gather samples T2, T2 has X below G)
   T2 → T1   (T2's gather samples T1, T1 has X below G)
   ...and every other pair.
```

Every tile mutually depends on every other tile → **cycle**. Kahn's topological sort drops cycle-bound nodes silently. Those four tiles never get scheduled. Nothing renders there.

The shape-level paint order (`X < G`) is a clean DAG. The cycle is an artifact of collapsing multi-shape tiles into a single node. The fix is finer granularity.

---

## 2. Core insight: split tiles into bands

Within a tile, group consecutive shapes (in paint order) into **bands** separated by gather barriers. A gather always sits at the head of its band.

```
   Paint order within tile T:
   ─────────────────────────────────────────▶
   │ A(0) │ B(1) │ G(2) │ C(3) │ D(4) │
   └──band 0─────┘└────── band 1 ──────┘
                  ▲
                  gather G sits at head of band 1
```

Scheduler nodes become `BandKey(tile, band_index)` instead of just `tile`. A tile with shapes `[A, B, G, C, D]` becomes two nodes. The scheduler visits tile T twice — once per band.

Bands turn the tile cycle into a **strict DAG**:

```
   T1-band1 ──┐                 T2-band1 ──┐
              ├──▶ T1-band0                ├──▶ T1-band0
              └──▶ T2-band0                └──▶ T2-band0
```

`T1-band1` depends on `T1-band0` and `T2-band0` (everything below G in paint order in its sample region). Crucially, `T1-band1` does **not** depend on `T2-band1`, and vice versa — they're peers, both at max paint order `po(G)`. No cycle.

---

## 3. Per-tile barriers (locality)

Splitting every tile at every gather barrier would be wasteful: a tile far from any gather would be forced into multiple bands for no reason.

A tile is only split by the gathers **whose sample regions reach it**. If tile U is outside every gather's sample region, it has zero barriers and stays as one band.

```
                     ┌────────────── viewport ──────────────┐
                     │                                       │
                     │    ╭───────╮                          │
                     │    │   G   │ sample region            │
                     │    │       │ extends one tile out     │
                     │    ╰───────╯                          │
                     │    ┌──┬──┬──┐                         │
                     │    │  │  │  │  ← these 3 tiles have   │
                     │    ├──┼──┼──┤    barriers = {po(G)}   │
                     │    │  │GG│  │                         │
                     │    ├──┼──┼──┤                         │
                     │    │  │  │  │                         │
                     │    └──┴──┴──┘                         │
                     │                                       │
                     │                        ┌──┐           │
                     │                        │  │ ← unaffected,
                     │                        └──┘   1 band, no
                     │                               overhead   │
                     └───────────────────────────────────────┘
```

Formally: for tile T, `barriers(T) = { po(G) | T ∈ G.sample_region }`, plus `po(G)` for any gather G that lives in T (ensures the gather sits at the head of a band in its own tile).

Within T, bands are formed by walking entries in paint order and starting a new band at each barrier boundary.

---

## 4. Dependency graph

Per-tile bands are numbered locally (0, 1, 2…), so cross-tile comparisons use paint-order ranges rather than band indices.

For each band headed by gather G (at paint order `g_po`) in tile T:

```
  for S in G.sample_region:
      for (s_idx, s_band) in bands[S]:
          if s_band.max_paint_order < g_po:
              deps[(T, G-band)].add((S, s_idx))
```

Because `g_po ∈ barriers(S)` by construction, S's bands split cleanly at `g_po` — the bands "below G" are a well-defined prefix of S's band list. Every edge points toward strictly lower paint orders → **strict DAG**.

---

## 5. Schedule execution — per-band visit lifecycle

A tile with one band runs exactly like the old scheduler: clear Current, render shapes, finalize to Target.

A tile with multiple bands needs state preserved between visits. The Current surface holds shapes rendered so far; we snapshot it at the end of a non-last band and restore it at the start of a non-first band.

### Per-visit decision table

Inside `run_schedule`, on each `SetTileBand { tile, band_index, is_first, is_last }`:

```
   previous-tile handling (before switching focus):
   ┌─────────────────────────┬─────────────────────────────────────────────┐
   │ prev band was last band │ apply_render_to_final_canvas(prev.rect)    │
   │                         │ drop_interband(prev.tile)                   │
   ├─────────────────────────┼─────────────────────────────────────────────┤
   │ prev band was interim   │ composite_current_to_target(prev.rect, bg) │
   │                         │    ← push prev band's content to Target    │
   │                         │      so other tiles' gather can sample it   │
   │                         │ snapshot_current_for_interband(prev.tile)   │
   │                         │    ← save for our next band restore         │
   └─────────────────────────┴─────────────────────────────────────────────┘

   current-tile setup (after switching focus):
   ┌──────────────────┬──────────────────────────────────────────┐
   │ is_first == true │ clear Current to bg (or cached_blit)     │
   ├──────────────────┼──────────────────────────────────────────┤
   │ is_first == false│ restore_current_from_interband(tile)     │
   │                  │    ← must succeed (debug_assert)         │
   └──────────────────┴──────────────────────────────────────────┘
```

When a band contains a gather and that `Render(G)` step fires, the existing glass path runs unchanged: pre-flush Current to Target within the tile rect, then `render_glass_with_backdrop(Current, Target)`.

### Why two things at an interim hand-off

An interim hand-off does both a **Target push** and a **snapshot**, because those serve different consumers:

- **Target push** → other tiles' gather bands (which will run later) must see this tile's below-content when they sample Target.
- **Snapshot** → *this* tile's next band visit must continue building on the Current it had at end-of-band, since subsequent shapes in the same tile layer on top.

Tiles with only one band never snapshot and never restore — the old fast path stands.

---

## 6. Worked example

### Scene

| Shape | `paint_order` | Gather? | Tiles occupied |
| --- | --- | --- | --- |
| X | 0 | no | (0,0), (1,0) |
| G | 1 | **yes** (glass) | (0,0), (1,0) |
| Y | 2 | no | (2,0) |

G's sample region = (0,0) ∪ (1,0).

### Step A — barriers per tile

```
   ╔═════════╦═════════╦═════════╗
   ║  (0,0)  ║  (1,0)  ║  (2,0)  ║
   ║ X, G    ║ X, G    ║ Y       ║
   ║ bar={1} ║ bar={1} ║ bar={ } ║
   ╚═════════╩═════════╩═════════╝
```

(2,0) is outside G's sample region ⇒ no barriers ⇒ one band.

### Step B — bands

| Tile | Shapes (po) | Bands |
| --- | --- | --- |
| (0,0) | X(0), G(1) | **band 0**=`[X]`, **band 1**=`[G]` |
| (1,0) | X(0), G(1) | **band 0**=`[X]`, **band 1**=`[G]` |
| (2,0) | Y(2) | **band 0**=`[Y]` (only) |

5 `BandKey` nodes total.

### Step C — deps

```
   (0,0)·band1 ──▶ (0,0)·band0
                ╲
                 ▶ (1,0)·band0

   (1,0)·band1 ──▶ (1,0)·band0
                ╲
                 ▶ (0,0)·band0

   (2,0)·band0 ──  no deps, no dependents
```

No edge between `(0,0)·band1` and `(1,0)·band1` — peers. DAG intact.

### Step D — schedule (topological + spiral tiebreak)

```
 1.  SetTileBand (0,0) band=0 first=true  last=false
 2.  Render X
 3.  SetTileBand (1,0) band=0 first=true  last=false
 4.  Render X
 5.  SetTileBand (2,0) band=0 first=true  last=true
 6.  Render Y
 7.  SetTileBand (0,0) band=1 first=false last=true
 8.  Render G       ← pre-flush + render_glass_with_backdrop
 9.  SetTileBand (1,0) band=1 first=false last=true
10.  Render G       ← pre-flush + render_glass_with_backdrop
```

### Step E — `run_schedule` trace

Target and Current state after each SetTileBand + its Renders:

```
 step  event                 Target (what's on it)                  Current at focus tile
─────  ────────────────────  ──────────────────────────────────     ─────────────────────
   1   SetTileBand (0,0)·0   (empty: bg everywhere)                 (0,0): bg
   2   Render X                                                     (0,0): X

   3   SetTileBand (1,0)·0   (0,0)-rect: X   ← interim flush        (0,0): snapshot=[X]
                              (1,0)-rect: bg                        (1,0): bg
   4   Render X                                                     (1,0): X

   5   SetTileBand (2,0)·0   (1,0)-rect: X   ← interim flush        (1,0): snapshot=[X]
                              (2,0)-rect: bg                        (2,0): bg
   6   Render Y                                                     (2,0): Y

   7   SetTileBand (0,0)·1   (2,0)-rect: Y   ← final flush           restore (0,0): [X]
                              drop (2,0)
   8   Render G              Current→Target at (0,0) (idempotent)   (0,0): X + G(sampled)
                              glass samples Target across region
                              = {X@(0,0), X@(1,0)} ✓

   9   SetTileBand (1,0)·1   (0,0)-rect: X+G ← final flush, drop     restore (1,0): [X]
  10   Render G              glass samples Target                    (1,0): X + G
                              = {X+G@(0,0), X@(1,0)} *

 end   finalize last         (1,0)-rect: X+G  ← final flush, drop
```

`*` At step 10, (1,0)'s gather sees (0,0)'s glass output in its backdrop. This peer-sampling is a known trade-off for multi-tile gathers in a single-pass scheduler (see §8).

### What tile (2,0) paid

Nothing extra. Its single band runs like the old scheduler: clear, render, finalize. No snapshot, no restore. This locality — tiles unaffected by any gather stay on the hot path — is the point of per-tile barriers.

---

## 7. Cost analysis

Let `T` = tiles in the interest rect, `S` = shapes, `G` = gather count, `r` = avg tiles per gather's sample region, `k` = avg shapes per tile.

| Phase | Old | New |
| --- | --- | --- |
| Shape indexing | `O(S · tiles_per_shape)` | unchanged |
| Spiral | `O(T log T)` | unchanged |
| Band computation | — | `O(G · r + T · k log k)` |
| Dep graph | `O(G · r · k)` | `O(G · r · (G+1))` |
| Topo sort | `O(T + E)` | `O(B + E')` where `B ≤ T + G · r` |
| Run schedule | `O(total render)` | `O(total render + G · r · tile_pixels)` |

**Rebuild** is asymptotically identical. Band computation adds a per-tile paint-order sort over a small `k`; dep-graph gets a small bump tied to G.

**Run** adds overhead proportional to the number of *gather-affected* tiles, not total tiles. A viewport with 50 tiles and one 4-tile gather pays extra snapshot/restore cost on those 4 tiles; the other 46 are unchanged.

**Memory**: transient `interband_cache: HashMap<Tile, skia::Image>` sized `O(G · r · tile_pixels)`, freed per-tile on the last-band visit and cleared at the end of each `run_schedule`.

---

## 8. Known limitations (not addressed by bands alone)

- **Peer-gather sampling**: when two tiles share the same gather (`band_index` = the gather's band), whichever runs first finalizes its glass to Target before the second's backdrop sample. The second tile's glass then partially samples the first's glass output in neighboring regions. Eliminating this requires deferring all gather-band finalizations until every peer has sampled — a second pass. Out of scope here.
- **Nested gathers** (gather inside another gather's subtree): single-pass scheduler can't express the required ordering. Separate design.
- **Container / frame gather**: the pre-flush composites to Target at tile rect; containers with their own scratch surface need a different approach. Root-level gather only.

---

## 9. Relevant files

- [`render-wasm/src/tile_grid.rs`](../src/tile_grid.rs) — scheduler data model, band computation, dep graph, `build_schedule`, `run_schedule`.
- [`render-wasm/src/render/surfaces.rs`](../src/render/surfaces.rs) — `Current` / `Target` surfaces, inter-band cache, `composite_current_to_target`, `apply_render_to_final_canvas`.
- [`render-wasm/src/render/glass.rs`](../src/render/glass.rs) — `render_glass_with_backdrop` (unchanged by this design).
