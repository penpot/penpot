import { createTestSuite, TestSuite, TestRunObserver, TestResult } from "./composable-tests";

// The plugin sandbox: it has the `penpot` API but no DOM. It enumerates the test
// suite once, sends the tree to the UI to render, and runs whichever tests the UI
// selects — forwarding each test's start/finish (addressed by stable id) so the UI
// can update the matching row.

penpot.ui.open("Composable Tests", `?theme=${penpot.theme}`, { width: 420, height: 640 });

penpot.on("themechange", (theme) => {
    penpot.ui.sendMessage({ type: "theme", theme });
});

// enumerate once; the tree the UI renders and the runs it requests share these ids
const suite: TestSuite = createTestSuite();

/** Sends the enumerated tree for the UI to render (all tests, not yet run). */
function sendTree(): void {
    penpot.ui.sendMessage({ type: "test-tree", tree: suite.tree() });
}

/** A `TestRunObserver` that forwards each test's state change to the UI by id. */
class UiReportingObserver implements TestRunObserver {
    onTestStarted(id: string): void {
        penpot.ui.sendMessage({ type: "test-started", id });
    }

    onTestFinished(id: string, result: TestResult): void {
        penpot.ui.sendMessage({
            type: "test-finished",
            id,
            result: {
                passed: result.passed,
                errorMessage: result.errorMessage,
                transcript: [...result.transcript],
            },
        });
    }
}

penpot.ui.onMessage<{ type?: string; ids?: string[] }>((message) => {
    if (typeof message !== "object" || message === null) return;

    if (message.type === "ready") {
        sendTree();
    } else if (message.type === "run-tests") {
        const ids = message.ids ?? [];
        suite
            .run(ids, new UiReportingObserver())
            .then(() => penpot.ui.sendMessage({ type: "run-complete" }))
            .catch((error: unknown) => {
                const errorMessage = error instanceof Error ? error.message : String(error);
                penpot.ui.sendMessage({ type: "run-error", errorMessage });
            });
    }
});

// in case the UI loaded before this handler was registered, also send the tree now
sendTree();
