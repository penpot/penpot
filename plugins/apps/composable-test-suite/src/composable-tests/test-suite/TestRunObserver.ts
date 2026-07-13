import { TestResult } from "./TestResult.ts";

/**
 * Receives streaming notifications as a selected set of tests runs, addressed by
 * stable test id, so a UI can update the specific row for each test the moment its
 * state changes. The set of tests to run is known up front (the UI selected them),
 * so there is no separate "total" notification — each test reports when it starts
 * and when it finishes.
 */
export interface TestRunObserver {
    /** Called when the test with id `id` begins running. */
    onTestStarted(id: string): void;

    /** Called when the test with id `id` finishes, with its result. */
    onTestFinished(id: string, result: TestResult): void;
}
