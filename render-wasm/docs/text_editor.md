# Text Editor Architecture

## Overview (Simplified)

```mermaid
flowchart TB
    subgraph Browser["Browser / DOM"]
        CE[contenteditable]
        Events[DOM Events]
    end

    subgraph CLJS["ClojureScript"]
        InputHandler[text_editor_input.cljs]
        Bindings[text_editor.cljs]
        ContentCache[(content cache)]
    end

    subgraph WASM["WASM Boundary"]
        FFI["_text_editor_* functions"]
    end

    subgraph Rust["Rust"]
        subgraph StateModule["state/text_editor.rs"]
            TES[TextEditorState]
            Selection[TextSelection]
            Cursor[TextCursor]
        end

        subgraph WASMImpl["wasm/text_editor.rs"]
            StateOps[start / stop]
            CursorOps[cursor / selection]
            EditOps[insert / delete]
            ExportOps[export content]
        end

        subgraph RenderMod["render/text_editor.rs"]
            RenderOverlay[render_overlay]
        end

        Shapes[(ShapesPool)]
    end

    subgraph Skia["Skia"]
        Canvas[Canvas]
        Paragraph[Paragraph layout]
    end

    %% Flow
    CE --> Events
    Events --> InputHandler
    InputHandler --> Bindings
    Bindings --> FFI
    FFI --> StateOps & CursorOps & EditOps & ExportOps

    StateOps --> TES
    CursorOps --> TES
    EditOps --> TES
    EditOps --> Shapes
    ExportOps --> Shapes
    TES --> Selection --> Cursor

    RenderOverlay --> TES
    RenderOverlay --> Shapes
    Shapes --> Paragraph
    RenderOverlay --> Canvas
    Paragraph --> Canvas

    ExportOps --> ContentCache
    ContentCache --> InputHandler
```

---

## Detailed Architecture

```mermaid
flowchart TB
    subgraph Browser["Browser / DOM"]
        CE[contenteditable element]
        KeyEvents[keydown / keyup]
        MouseEvents[mousedown / mousemove]
        IME[compositionstart / end]
    end

    subgraph CLJS["ClojureScript Layer"]
        subgraph InputMod["text_editor_input.cljs"]
            EventHandler[Event Handler]
            BlinkLoop[RAF Blink Loop]
            SyncFn[sync-content!]
        end

        subgraph BindingsMod["text_editor.cljs"]
            direction TB
            StartStop[start / stop]
            CursorFns[set-cursor / move]
            SelectFns[select-all / extend]
            EditFns[insert / delete]
            ExportFns[export-content]
            StyleFns[apply-style]
        end

        ContentCache[(shape-text-contents<br/>atom)]
    end

    subgraph WASM["WASM Boundary"]
        direction TB
        FFI_State["_text_editor_start<br/>_text_editor_stop<br/>_text_editor_is_active"]
        FFI_Cursor["_text_editor_set_cursor_from_point<br/>_text_editor_move_cursor<br/>_text_editor_select_all"]
        FFI_Edit["_text_editor_insert_text<br/>_text_editor_delete_backward<br/>_text_editor_insert_paragraph"]
        FFI_Query["_text_editor_export_content<br/>_text_editor_get_selection<br/>_text_editor_poll_event"]
        FFI_Render["_text_editor_render_overlay<br/>_text_editor_update_blink"]
    end

    subgraph Rust["Rust Layer"]
        subgraph StateMod["state/text_editor.rs"]
            TES[TextEditorState]
            Selection[TextSelection]
            Cursor[TextCursor]
            Events[EditorEvent queue]
        end

        subgraph WASMMod["wasm/text_editor.rs"]
            direction TB
            WStateOps[State ops]
            WCursorOps[Cursor ops]
            WEditOps[Edit ops]
            WQueryOps[Query ops]
        end

        subgraph RenderMod["render/text_editor.rs"]
            RenderOverlay[render_overlay]
            RenderCursor[render_cursor]
            RenderSelection[render_selection]
        end

        Shapes[(ShapesPool<br/>TextContent)]
    end

    subgraph Skia["Skia"]
        Canvas[Canvas]
        SkParagraph[textlayout::Paragraph]
        TextBoxes[get_rects_for_range]
    end

    %% Browser to CLJS
    CE --> KeyEvents & MouseEvents & IME
    KeyEvents --> EventHandler
    MouseEvents --> EventHandler
    IME --> EventHandler

    %% CLJS internal
    EventHandler --> StartStop & CursorFns & EditFns & SelectFns
    BlinkLoop --> FFI_Render
    SyncFn --> ExportFns
    ExportFns --> ContentCache
    ContentCache --> SyncFn
    StyleFns --> ContentCache

    %% CLJS to WASM
    StartStop --> FFI_State
    CursorFns --> FFI_Cursor
    SelectFns --> FFI_Cursor
    EditFns --> FFI_Edit
    ExportFns --> FFI_Query

    %% WASM to Rust impl
    FFI_State --> WStateOps
    FFI_Cursor --> WCursorOps
    FFI_Edit --> WEditOps
    FFI_Query --> WQueryOps
    FFI_Render --> RenderOverlay

    %% Rust internal
    WStateOps --> TES
    WCursorOps --> TES
    WEditOps --> TES
    WEditOps --> Shapes
    WQueryOps --> TES
    WQueryOps --> Shapes

    TES --> Selection
    Selection --> Cursor
    TES --> Events

    %% Render flow
    RenderOverlay --> RenderCursor & RenderSelection
    RenderCursor --> TES
    RenderSelection --> TES
    RenderCursor --> Shapes
    RenderSelection --> Shapes

    %% Skia
    Shapes --> SkParagraph
    SkParagraph --> TextBoxes
    RenderCursor --> Canvas
    RenderSelection --> Canvas
```

---

## Key Files

| Layer | File | Purpose |
|-------|------|---------|
| DOM | - | contenteditable captures keyboard/IME input |
| CLJS | `text_editor_input.cljs` | Event handling, blink loop, content sync |
| CLJS | `text_editor.cljs` | WASM bindings, content cache, style application |
| Rust | `state/text_editor.rs` | TextEditorState, TextSelection, TextCursor |
| Rust | `wasm/text_editor.rs` | WASM exported functions |
| Rust | `render/text_editor.rs` | Cursor & selection overlay rendering |

## Data Flow

1. **Input**: DOM events → ClojureScript handler → WASM function → Rust state
2. **Edit**: Rust modifies TextContent in ShapesPool → triggers layout
3. **Sync**: Export content → merge with cached styles → update shape
4. **Render**: RAF loop → render_overlay → Skia draws cursor/selection
