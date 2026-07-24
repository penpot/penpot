import { TestSuite } from "./test-suite/TestSuite.ts";
import { allCases } from "./cases";

/**
 * Builds the enumerated test suite for the live Penpot document. The suite expands
 * every case into its concrete variants once (assigning stable ids) and serves
 * both the tree the UI renders and the runs it requests. Intended to be created
 * once by the plugin, which then sends its `tree()` to the UI and runs selected
 * ids on demand.
 */
export function createTestSuite(): TestSuite {
    return new TestSuite(allCases());
}

export { TestSuite } from "./test-suite/TestSuite.ts";
export type { TestRunObserver } from "./test-suite/TestRunObserver.ts";
export type { TestTree, TestGroupInfo, TestInfo } from "./test-suite/TestTree.ts";
export { TestResult } from "./test-suite/TestResult.ts";
