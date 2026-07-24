import { createTestSuite, TestRunObserver, TestResult } from "../composable-tests";

// In-sandbox CI entry point. Built as a standalone IIFE bundle (dist/headless.js)
// and evaluated inside a real Penpot plugin sandbox by the out-of-sandbox driver
// `ci/run-ci.ts` (note: a different `ci/` directory). It runs the composable test
// suite without any UI and reports each result through `console.log` markers that
// the Playwright driver parses. The marker protocol mirrors the one used by the
// plugin-api-test-suite.

/** What the driver receives for each finished test. */
interface ReportedResult {
    /** The composite case identifier, e.g. "MainEditSyncs-2". */
    identifier: string;
    /** The test's descriptive name (identifies the variant's choices). */
    name: string;
    passed: boolean;
    durationMs: number;
    error?: string;
    /** The applied steps, for diagnosing failures from the CI log. */
    transcript?: readonly string[];
}

async function main() {
    const suite = createTestSuite();
    const tree = suite.tree();

    // Composite identifiers ("Identifier-N", 1-based) for reporting, and the
    // id order matching the tree — the same addressing the panel UI uses.
    const identifierById = new Map<string, string>();
    const orderedIds: string[] = [];
    for (const group of tree.groups) {
        group.tests.forEach((test, i) => {
            identifierById.set(test.id, `${group.identifier}-${i + 1}`);
            orderedIds.push(test.id);
        });
    }

    // Set by the driver from TEST_FILTER: run only tests whose composite
    // identifier contains the given substring (case-insensitive), e.g.
    // "MainEditSyncs" (whole case) or "MainEditSyncs-2" (single variant).
    const filter = (
        globalThis as unknown as { __COMPOSABLE_SUITE_FILTER__?: string }
    ).__COMPOSABLE_SUITE_FILTER__?.toLowerCase();
    const ids = filter ? orderedIds.filter((id) => identifierById.get(id)!.toLowerCase().includes(filter)) : orderedIds;

    const startedAt = new Map<string, number>();
    let failed = 0;

    const observer: TestRunObserver = {
        onTestStarted(id: string): void {
            startedAt.set(id, Date.now());
        },
        onTestFinished(id: string, result: TestResult): void {
            if (!result.passed) failed++;
            const reported: ReportedResult = {
                identifier: identifierById.get(id) ?? id,
                name: result.name,
                passed: result.passed,
                durationMs: Date.now() - (startedAt.get(id) ?? Date.now()),
                error: result.errorMessage,
                transcript: result.passed ? undefined : result.transcript,
            };
            console.log("__TEST_RESULT__ " + JSON.stringify(reported));
        },
    };

    await suite.run(ids, observer);
    console.log("__TEST_DONE__ " + JSON.stringify({ total: ids.length, failed }));
}

main().catch((err: unknown) => {
    const message = err instanceof Error ? (err.stack ?? err.message) : String(err);
    console.log("__TEST_FATAL__ " + JSON.stringify({ message }));
});
