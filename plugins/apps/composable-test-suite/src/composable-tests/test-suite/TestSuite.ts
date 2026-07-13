import { TestCase } from "./TestCase.ts";
import { RunnableTest } from "./RunnableTest.ts";
import { TestRunObserver } from "./TestRunObserver.ts";
import { TestTree } from "./TestTree.ts";

/** A group of runnable tests enumerated from one case. */
interface Group {
    identifier: string;
    description: string;
    tests: RunnableTest[];
}

/**
 * The enumerated test suite. On construction it expands every case into its
 * concrete variants ONCE, assigning each a stable opaque id, and holds them
 * grouped by case and addressable by id. Both the rendered tree and any run are
 * served from this single held enumeration, so the ids the UI sees and the ids it
 * runs are guaranteed to refer to the same tests.
 *
 * Running a selected subset streams per-test notifications through a
 * `TestRunObserver`; each test rebuilds its configuration fresh, so tests do not
 * interfere and a failure does not stop the rest.
 */
export class TestSuite {
    private readonly groups: Group[];
    private readonly byId: Map<string, RunnableTest>;

    constructor(cases: readonly TestCase[]) {
        this.groups = TestSuite.enumerateGroups(cases);
        this.byId = new Map(this.groups.flatMap((group) => group.tests.map((test) => [test.id, test])));
    }

    /** The serialisable tree (groups and their tests) for the UI to render. */
    tree(): TestTree {
        return {
            groups: this.groups.map((group) => ({
                identifier: group.identifier,
                description: group.description,
                tests: group.tests.map((test) => ({ id: test.id, name: test.name })),
            })),
        };
    }

    /** All test ids, in enumeration order (e.g. for a "run all"). */
    allIds(): string[] {
        return this.groups.flatMap((group) => group.tests.map((test) => test.id));
    }

    /**
     * Runs the tests with the given `ids` (unknown ids are skipped), reporting each
     * test's start and finish through `observer`, in the given order.
     */
    async run(ids: readonly string[], observer: TestRunObserver): Promise<void> {
        let posY = 0;
        for (const id of ids) {
            const test = this.byId.get(id);
            if (test === undefined) continue;
            observer.onTestStarted(id);
            const result = await test.run(posY);
            posY += 150; // advance y-position for next test
            observer.onTestFinished(id, result);
        }
    }

    /** Expands each case into a group of runnable tests with stable ids. */
    private static enumerateGroups(cases: readonly TestCase[]): Group[] {
        return cases.map((testCase, caseIndex) => {
            const operations = testCase.operation.enumerateVariants();
            const tests = operations.map((operation, i) => {
                return new RunnableTest(`t${caseIndex}_${i}`, `instance #${i + 1}`, operation);
            });
            return { identifier: testCase.identifier, description: testCase.description, tests };
        });
    }
}
