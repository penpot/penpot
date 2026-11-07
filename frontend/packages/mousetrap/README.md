# @penpot/mousetrap

Vendored keyboard-shortcut library (based on Mousetrap 1.6.5), migrated
from `code`/`keyCode` to `event.key` with lowercase canonical tokens so
shortcuts behave the same across keyboard layouts.

## Tests

Unit suite with the Node standard runner, no browser and no extra
dependencies:

```bash
pnpm test          # node --test "test/*.test.js"
```

The suite builds real `Mousetrap` instances over fake targets and
synthetic events, with fake timers for sequences. It runs in the frontend
CI through `frontend/scripts/test` and must stay deterministic (no
network, no real time, no platform reads).
