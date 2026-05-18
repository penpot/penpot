# V3 Text Editor (`text-editor-wasm/v1`)

The V3 editor is the WASM-native text editor: all editing logic in Rust, cursor and selection rendered as Skia overlays on `SurfaceId::UI`. The CLJS side is a thin contenteditable wrapper that captures input events and forwards them to WASM.

---

## Editor generations

| Gen | File | Feature flag | Notes |
|-----|------|--------------|-------|
| V1 | `frontend/src/app/main/ui/workspace/shapes/text/editor.cljs` | (none / default) | Legacy Draft-JS |
| V2 | `frontend/src/app/main/ui/workspace/shapes/text/v2_editor.cljs` | `text-editor/v2` | DOM-based contenteditable, JS logic |
| V3 | `frontend/src/app/main/ui/workspace/shapes/text/v3_editor.cljs` | `text-editor-wasm/v1` | WASM-native, Skia overlay |

Selection logic in `viewport_wasm.cljs:467-480` checks flags in order: V3 → V2 → V1.

## Feature flags (`frontend/src/app/main/features.cljs:38-41`)

- `render-wasm/v1` — enables WASM rendering; **automatically enables** `text-editor/v2`
- `text-editor/v2` — V2 editor (auto-enabled with render-wasm)
- `text-editor-wasm/v1` — V3 editor (independent flag, only functional with render-wasm active)

---

## Data flow

```
User input on contenteditable div
  → v3_editor.cljs event handler (keydown / beforeinput / compositionend / paste / cut / copy / dblclick)
  → wasm.api/text-editor-* call (via render_wasm/text_editor.cljs)
  → state/text_editor.rs processes operation (insert, delete, move, select)
  → TextEditorState updated (selection, content, blink)
  → text_editor_export_content() → JSON {paragraphs, spans, text}
  → CLJS merge-exported-texts-into-content (preserves per-span styling)
  → v2-update-text-shape-content Redux action
  → request-render → Skia draws text + overlay (cursor / selection)
```

Two key implications:

1. After every text mutation, the CLJS side must call `sync-wasm-text-editor-content!` to export and update Redux state. Forgetting this leaves the DOM and WASM out of sync.
2. Cursor and selection are NOT DOM. They live in `TextEditorState` and render via `text_editor_render_overlay()` to `SurfaceId::UI`.

---

## Key files

### Rust / WASM

- `render-wasm/src/state/text_editor.rs` — `TextEditorState` struct: selection, blink, active shape, pointer tracking
- `render-wasm/src/wasm/text_editor.rs` — `#[no_mangle]` exports: start/stop, cursor, selection, editing, navigation, render overlay
- `render-wasm/src/wasm/text/helpers.rs` — word boundary detection, cursor movement (forward/backward/up/down/line-start/end), deletion, insertion
- `render-wasm/src/render/text_editor.rs` — `render_overlay()`, `calculate_cursor_rect()`, `calculate_selection_rects()` using Skia

### Frontend (CLJS)

- `frontend/src/app/main/ui/workspace/shapes/text/v3_editor.cljs` — V3 component (contenteditable + event handlers)
- `frontend/src/app/render_wasm/text_editor.cljs` — JS FFI wrappers for all WASM text editor functions
- `frontend/src/app/render_wasm/api.cljs` — public API re-exports, render loop integration (blink, overlay, poll-event)

---

## WASM export functions (`render-wasm/src/wasm/text_editor.rs`)

| Group | Exports |
|---|---|
| **Lifecycle** | `text_editor_start(a,b,c,d)`, `text_editor_stop()` |
| **Cursor** | `text_editor_set_cursor_from_offset(x,y)`, `text_editor_set_cursor_from_point(x,y)` |
| **Selection** | `text_editor_pointer_down/move/up(x,y)`, `text_editor_select_word_boundary(x,y)`, `text_editor_select_all()` |
| **Editing** | `text_editor_insert_text()`, `text_editor_delete_backward(word_boundary)`, `text_editor_delete_forward(word_boundary)`, `text_editor_insert_paragraph()` |
| **Navigation** | `text_editor_move_cursor(direction, word_boundary, extend_selection)` |
| **Rendering** | `text_editor_render_overlay()`, `text_editor_update_blink(timestamp_ms)` |
| **Events** | `text_editor_poll_event()` → `TextEditorEvent` (`ContentChanged`, `SelectionChanged`, `NeedsLayout`) |
| **Export** | `text_editor_export_content()`, `text_editor_export_selection()` |

`text_editor_move_cursor` direction enum: `0`=Backward, `1`=Forward, `2`=LineBefore, `3`=LineAfter, `4`=LineStart, `5`=LineEnd. Boolean flags: `word_boundary`, `extend_selection`.

**Direction/flag enum drift is a common source of bugs** — keep CLJS-side constants in lockstep with Rust enums.

---

## V2 vs V3 differences

| Aspect | V2 | V3 |
|---|---|---|
| Text operations | JS / DOM mutations | Rust / WASM |
| Cursor | DOM native | WASM state + Skia overlay |
| Selection | DOM native | WASM state + Skia overlay |
| State location | JS objects + Redux | WASM `TextEditorState` |
| Styling | Direct DOM style manipulation | WASM stores per-span; exports JSON |
| Synchronization | Event-driven DOM | Explicit export after each WASM op |

---

## Common pitfalls

### Skia layout dependency for cursor rect

`calculate_cursor_rect` calls `get_rects_for_range()` and `get_line_metrics()` on the Skia paragraph. These return correct values only after the paragraph has been laid out (`paragraph.layout(width)`). If a cursor query fires before layout, it can return zeroed or stale rects.

**Fix shape:** always check that the paragraph is laid out before querying. The hook point in CLJS is `update-text-rect!` (in `api.cljs`), which fires after WASM layout completes.

### Cursor blink across start/stop

`text_editor_update_blink(timestamp_ms)` is called every frame. Verify that blink doesn't break on start/stop transitions — the blink phase resets on cursor movement, but a missed reset can leave the cursor invisible.

### Multi-line / cross-paragraph selection

`calculate_selection_rects()` must handle multi-line spans, cross-paragraph selections, and RTL text. RTL is the easy thing to forget; if RTL is in scope, write a test for it.

### Word boundary

`is_word_char()` (in `wasm/text/helpers.rs`) treats alphanumeric + underscore as word chars. Verify behavior matches platform conventions when adding new word-boundary cases (CJK, combining marks, emoji).

### Content export format

`text_editor_export_content()` returns JSON with `{paragraphs, spans, text}`. Verify it matches what `merge-exported-texts-into-content` in CLJS expects — schema drift here silently corrupts text content.

### Event handlers in `v3_editor.cljs`

Should handle: `keydown` (navigation, deletion), `beforeinput` (text insert), `compositionend` (IME), `paste`, `copy`, `cut`, `dblclick` (word select). Missing one of these doesn't immediately break the editor but leaves a feature gap.

### Feature flag gating

New V3 behavior should be behind `text-editor-wasm/v1`. Don't leak V3 changes into V2 paths — V2 stays as it is until V3 fully replaces it.

---

## Architectural distinctions

- **Dual rendering still applies.** The contenteditable DOM has `color: transparent`; Skia draws the visible text. V3 changes *only the cursor and selection* from DOM-native to Skia overlay; the visible text is still Skia-rendered in both V2 and V3.
- **Editor instance** stored at `:workspace-editor` in app state; root DOM via `(.-root editor)`.
- **State export** is the synchronization mechanism. After any WASM mutation, export → merge into CLJS content → dispatch to Redux. The merge is non-trivial because spans carry styling that the WASM side stores per-span.
