import { getTestMetas, setTests } from './framework/registry';
import type { TestCase } from './framework/types';
import { runTests } from './framework/runner';
import type { PluginToUIMessage, UIToPluginMessage } from './model';

// Auto-discover every test. Importing the modules eagerly runs their top-level
// `test(...)` calls, which register them into the shared registry.
import.meta.glob('./tests/*.test.ts', { eager: true });

penpot.ui.open('Plugin API Test Suite', `?theme=${penpot.theme}`, {
  width: 400,
  height: 600,
});

function send(message: PluginToUIMessage) {
  penpot.ui.sendMessage(message);
}

// Set by a `stop` message and read between tests by the runner. Reset at the
// start of every run.
let stopRequested = false;

penpot.ui.onMessage<UIToPluginMessage>(async (message) => {
  if (message.type === 'ready') {
    send({ type: 'tests', tests: getTestMetas() });
    return;
  }

  if (message.type === 'stop') {
    stopRequested = true;
    return;
  }

  if (message.type === 'run') {
    stopRequested = false;
    const { summary, coverage, stopped } = await runTests(
      message.ids,
      (result) => send({ type: 'result', result }),
      { shouldStop: () => stopRequested },
    );
    send({ type: 'runComplete', summary, coverage, stopped });
    return;
  }

  if (message.type === 'reloadTests') {
    try {
      // The runtime is configured with `evalTaming: 'unsafeEval'`, so evaluating
      // the freshly built IIFE bundle is allowed. It publishes the discovered
      // tests on `globalThis.__penpotReloadedTests`, which we swap into the
      // registry so the next run uses the edited code.
      const globals = globalThis as unknown as {
        __penpotReloadedTests?: TestCase[];
      };
      globals.__penpotReloadedTests = undefined;
      (0, eval)(message.code);
      const reloaded = globals.__penpotReloadedTests;
      if (!reloaded) {
        throw new Error('Reloaded bundle did not expose any tests');
      }
      setTests(reloaded);
      send({ type: 'tests', tests: getTestMetas() });
      send({ type: 'reloaded', ok: true });
    } catch (err) {
      send({
        type: 'reloaded',
        ok: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }
});

penpot.on('themechange', () => {
  send({ type: 'theme', theme: penpot.theme });
});
