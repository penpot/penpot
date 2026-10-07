# WASM Text Editor IME Input

IME (Japanese/CJK composition) in the v3 WASM text editor, `app.main.ui.workspace.shapes.text.v3-editor`. The WASM editor (`app.render-wasm.text-editor`, main-thread `_text_editor_*` exports) owns text, caret, and the composition preview; the DOM only captures input.

## Capture surface model

- Input lands in a hidden contenteditable (`#text-editor-wasm-input`, opacity 0, `pointer-events:none`) inside the editor `foreignObject`, wrapped by a positioned `.ime-anchor` div. Native IMEs anchor their candidate window to the DOM caret inside it.
- Idle: the surface is placed ON the WASM caret (`update-ime-caret!`) after every caret-affecting operation, collapsed along the inline axis (`scaleX`/`scaleY` ≈ 0.001) so retained text never moves the DOM caret. Vertical text uses `writing-mode:vertical-rl` and sits one column right of the caret column (macOS Chrome/Firefox: on the column).
- Mid-composition: NEVER write the composing element (style, text, selection) and never dispatch to the store; real IMEs (mozc on ibus/fcitx) abort and commit on any such mutation, including style writes on the keydown-229 before `compositionstart`. Only the wrapper moves (`follow-ime-caret!`: transform, or left/top on macOS vertical), by the WASM caret delta from the idle anchor.
- The WASM preview lives in WASM state (`text-editor-composition-update`); the store syncs on `compositionend` only. Preview fallback fonts must be requested explicitly (`load-composition-fonts!`), or uncommitted glyphs render as squares.
- Composition caret placement: on Linux+Chromium (`follow-ime-cursor?`) it tracks the IME's own cursor (the DOM selection, read on `input` with `insertCompositionText`); elsewhere it goes to the end of the changed text (`changed-span-end`) and later cursor-only moves mirror the DOM selection.
- Chromium: programmatic `.focus()` on a contenteditable inside SVG `foreignObject` does not fire focus/focusin; establish WASM focus explicitly (`focus-editor!`).
- If the anchored caret falls outside the `foreignObject` box, Chrome scrolls it (`scrollLeft`) and cancels the offset; vertical text widens the foreignObject, not the clip rect.

## Platform IME behavior

- Chrome on Linux X11 (GTK IM path, `ui/gtk/input_method_context_impl_gtk.cc`) passes caret bounds to the IME ONLY inside `DispatchKeyEvent` (key press and release); `SetCursorLocation` just stores them. An anchor move caused by the IME's reply that arrives after the key release (mozc TAB prediction is slow) leaves the candidate window stale until the next key. No page-side workaround: moving the anchor by transform or by layout makes no difference, and synthetic key events never reach the browser process. Keep the anchor still between keys instead.
- Blink side is not the bottleneck: the renderer recomputes selection bounds every main frame (`WidgetBase::WillBeginMainFrame` → `UpdateSelectionBounds`), and a layout-tree update invalidates its cache.
- mozc cursor: end of the preedit while typing; start of the active clause during conversion/prediction (stays there while cycling candidates). The first TAB/Space of a conversion moves it back to the clause start, so that one key lags on Linux Chrome, exactly as in a plain Chrome textarea.
- Synthetic input (`cljs_repl`, CDP `Input.imeSetComposition`) bypasses the OS IME: it validates event flow and WASM preview/commit, but cannot reproduce aborts, candidate-window position, or long-lived uncommitted preedits (real users often leave the preedit uncommitted for seconds).

## Debugging

- Geometry overlay: `debug.toggle_debug('ime')` (no reload). Blue = WASM caret, yellow = DOM caret, pink = DOM composition range (what Chromium reports to the IME); values are viewport CSS px. The native candidate window position is not visible to the page.
- When only the user reproduces: install capture-phase document listeners (keydown/keyup/composition*/input) logging `Date.now()`, `getSelection().focusOffset`, the wrapper style and the DOM caret rect; with the page `screenX/screenY`, `devicePixelRatio`, `outerHeight - innerHeight*dpr` (browser chrome height) to map viewport → screen px. Page listeners and `set!` patches vanish on reload; reinstall.
- Real candidate window position on X11/fcitx5: find `Fcitx5 Input Window` with `xdotool search --class fcitx`, then poll `xwininfo -id <id>` (absolute X/Y, size, Map State) every ~30 ms in a background loop, logging only changes with timestamps; correlate with the page log. Pressing a lone modifier (Ctrl) that snaps a stale window to the right place confirms a key-event-only bounds update.
- Screen recordings (e.g. Peek) of a user repro: extract timestamped contact sheets with `ffmpeg -vf "fps=2,scale=606:-1,drawtext=text='%{pts\:hms}':x=5:y=5:fontsize=20:fontcolor=yellow:box=1:boxcolor=black,tile=2x3"` and read them with the overlay panel visible.
- Driving the real IME from scripts on X11: `fcitx5-remote -s mozc` / restore the previous IM (`-n` to query), `xdotool windowactivate --sync`, `xdotool type --delay 250`, `xdotool key Tab|space|Return`. Check the screen is unlocked and on first, and get the user's consent: it takes over their keyboard.
