---
date: 2024-11-24T18:55:17-06:00
researcher: NaniSkinner
git_commit: 553b73a83c02b7c65421decbb2d173699928ebec
branch: develop
repository: PenPot
topic: "Add Numpad Key Aliases for Zoom Shortcuts (Shift+Num0/Num1/Num2)"
tags: [research, codebase, keyboard-shortcuts, zoom, numpad, mousetrap, clojurescript]
status: complete
last_updated: 2024-11-24
last_updated_by: NaniSkinner
---

# Research: Add Numpad Key Aliases for Zoom Shortcuts (Shift+Num0/Num1/Num2)

**Date**: 2024-11-24 18:55:17 CST
**Researcher**: NaniSkinner
**Git Commit**: 553b73a83c02b7c65421decbb2d173699928ebec
**Branch**: develop
**Repository**: PenPot

## Research Question
Implement keyboard shortcut aliases that allow users to use their numpad keys for zoom controls, making Shift+Num0, Shift+Num1, and Shift+Num2 behave identically to the existing Shift+0, Shift+1, and Shift+2 shortcuts (which zoom to fit, 100%, and 200% respectively). Currently, Penpot only recognizes the number row keys for these shortcuts, which creates a usability gap for users with full-size keyboards who naturally reach for the numpad, or for users who rely on external numpads for efficiency. This addresses GitHub Issue #2457, labeled as a "good first issue."

## Summary
The current Penpot keyboard shortcut system uses the Mousetrap library for keyboard event handling, which already maps numpad keys (keycodes 96-105) to their string digit equivalents ("0"-"9"). However, the zoom shortcuts are explicitly bound to "shift+0", "shift+1", and "shift+2", which should theoretically work with numpad keys since Mousetrap normalizes both number row and numpad keys to the same string values. The research reveals that the implementation change needed is minimal - updating the shortcut command definitions in `/frontend/src/app/main/data/workspace/shortcuts.cljs` to include numpad aliases by adding the normalized numpad key combinations to the existing command arrays.

## Detailed Findings

### Mousetrap Library - Numpad Support Already Built-in

**Location**: `/Users/nanis/dev/Gauntlet/PenPot/frontend/vendor/mousetrap/index.js:155-163`

The Mousetrap library already handles numpad key mapping:

```javascript
// Loop through to map numbers on the numeric keypad
for (i = 0; i <= 9; ++i) {
  // This needs to use a string cause otherwise since 0 is falsey
  // mousetrap will never fire for numpad 0 pressed as part of a keydown
  // event.
  _MAP[i + 96] = i.toString();
}
```

This maps:
- Keycode 96 → "0" (Numpad 0)
- Keycode 97 → "1" (Numpad 1)
- Keycode 98 → "2" (Numpad 2)
- ...continuing through Numpad 9

The library treats both number row keys and numpad keys as the same string value internally, which means "shift+0" should theoretically match both Shift+Number Row 0 and Shift+Numpad 0.

### Current Zoom Shortcut Definitions

**Location**: `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/shortcuts.cljs:506-519`

The current zoom shortcuts are defined as:

```clojure
:reset-zoom           {:tooltip (ds/shift "0")
                       :command "shift+0"
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/reset-zoom)}

:fit-all              {:tooltip (ds/shift "1")
                       :command "shift+1"
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/zoom-to-fit-all)}

:zoom-selected        {:tooltip (ds/shift "2")
                       :command ["shift+2" "@" "\""]
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/zoom-to-selected-shape)}
```

Note that `:zoom-selected` already uses an array format to support multiple key combinations ("shift+2", "@", and "\""), demonstrating that the system supports multiple aliases for the same action.

### Zoom Action Implementations

**Location**: `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/zoom.cljs`

The zoom actions triggered by these shortcuts:

1. **reset-zoom** (lines 81-86): Sets zoom to 1 (100%)
2. **zoom-to-fit-all** (lines 88-105): Fits all shapes in viewport with 160px padding
3. **zoom-to-selected-shape** (lines 107-126): Fits selected shapes with 40px padding

Note: The description mentions "Shift+2 zooms to 200%" but the actual implementation is `zoom-to-selected-shape`, not a fixed 200% zoom.

### Keyboard Event Flow

1. **DOM Event Capture** (`/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/ui/workspace/viewport/hooks.cljs:70-71`)
   - Global keyboard listeners on document

2. **Event Processing** (`/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/ui/workspace/viewport/actions.cljs:310-345`)
   - Creates custom KeyboardEvent records with key, modifiers, and editing state

3. **Mousetrap Binding** (`/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/shortcuts.cljs:149-161`)
   - Shortcuts bound via `mousetrap/bind` with command strings

4. **Action Dispatch**
   - Shortcut callbacks emit Potok events to update state

### Existing Pattern for Multiple Key Aliases

Several shortcuts already use arrays for multiple key bindings:

**Location**: `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/shortcuts.cljs`

Examples:
- Line 118: `:delete` → `["del" "backspace"]`
- Line 230: `:move-fast-up` → `["shift+up" "shift+alt+up"]`
- Line 497: `:increase-zoom` → `["+" "="]`
- Line 502: `:decrease-zoom` → `["-" "_"]`
- Line 279: `:draw-frame` → `["b" "a"]`

This pattern is well-established throughout the codebase.

### Implementation Requirements

Based on the research, the implementation requires updating three shortcut definitions in `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/shortcuts.cljs`:

1. Change `:reset-zoom` command from `"shift+0"` to an array including numpad variant
2. Change `:fit-all` command from `"shift+1"` to an array including numpad variant
3. The `:zoom-selected` command already uses an array, so add the numpad variant

Since Mousetrap already normalizes numpad keys to digit strings, the commands should work as-is. However, if explicit numpad support is needed, the library also accepts "numpad0", "numpad1", etc. as valid key identifiers.

### Testing Considerations

The implementation should be tested with:
1. Standard keyboards with numpad
2. External USB numpads
3. Different operating systems (Windows, macOS, Linux)
4. Different browsers (Chrome, Firefox, Safari)
5. Verify no conflicts with existing shortcuts
6. Test with Num Lock on and off

## Code References

- `/Users/nanis/dev/Gauntlet/PenPot/frontend/vendor/mousetrap/index.js:155-163` - Numpad key mapping in Mousetrap
- `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/shortcuts.cljs:506-519` - Current zoom shortcut definitions
- `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/zoom.cljs:81-126` - Zoom action implementations
- `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/shortcuts.cljs:149-161` - Mousetrap binding logic
- `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/ui/workspace/viewport/hooks.cljs:463-465` - Shortcut registration
- `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/ui/workspace/viewport/actions.cljs:310-345` - Keyboard event processing

## Architecture Documentation

### Shortcut System Architecture

The Penpot keyboard shortcut system follows a layered architecture:

1. **Browser Layer**: Native DOM keyboard events
2. **Mousetrap Library**: Third-party library that normalizes keyboard events across browsers
3. **ClojureScript Wrapper**: Custom KeyboardEvent records and event streams
4. **Shortcut Registry**: Stack-based system allowing context-specific overrides
5. **Action Dispatch**: Potok events that modify application state
6. **UI Updates**: React components re-render based on state changes

### Key Design Patterns

1. **Command Arrays**: Shortcuts can specify multiple key combinations via arrays
2. **Context Stacking**: Different modes (text, path, grid editing) push/pop their shortcuts
3. **Reactive Streams**: RxJS streams for modifier key states and continuous actions
4. **Platform Abstraction**: `mod` key maps to Cmd on macOS, Ctrl elsewhere
5. **Permission Checks**: Edit operations verify user permissions before execution

### Mousetrap Integration Points

The system integrates with Mousetrap at these key points:
- Import: `["@penpot/mousetrap$default" :as mousetrap]`
- Binding: `mousetrap/bind` in `bind!` function
- Unbinding: `mousetrap/reset` in `reset!` function
- Key normalization: Handled internally by Mousetrap

## Related Research

No existing research documents found for keyboard shortcuts or zoom functionality in the thoughts/shared/research directory.

## Open Questions

1. **Browser Compatibility**: While Mousetrap normalizes numpad keys, are there any known browser-specific issues with Shift+Numpad combinations?

2. **Num Lock State**: How does the system behave when Num Lock is off? The research suggests Mousetrap handles this, but testing is needed.

3. **Internationalization**: Do non-US keyboard layouts affect numpad key detection?

4. **Accessibility**: Should the UI tooltip show both shortcuts (e.g., "Shift+0 or Shift+Num0") or keep the current simple display?

5. **Future Shortcuts**: Should all numeric shortcuts in Penpot support numpad variants for consistency?

## Implementation Recommendation

The minimal change required is to update the shortcut definitions in `/Users/nanis/dev/Gauntlet/PenPot/frontend/src/app/main/data/workspace/shortcuts.cljs`:

```clojure
:reset-zoom           {:tooltip (ds/shift "0")
                       :command ["shift+0" "shift+numpad0"]  ; Add numpad variant
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/reset-zoom)}

:fit-all              {:tooltip (ds/shift "1")
                       :command ["shift+1" "shift+numpad1"]  ; Add numpad variant
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/zoom-to-fit-all)}

:zoom-selected        {:tooltip (ds/shift "2")
                       :command ["shift+2" "@" "\"" "shift+numpad2"]  ; Add numpad variant
                       :subsections [:zoom-workspace]
                       :fn #(st/emit! dw/zoom-to-selected-shape)}
```

However, testing should first verify if the existing shortcuts already work with numpad keys due to Mousetrap's normalization. If they do, no code changes may be needed, and the issue might be browser or OS-specific.