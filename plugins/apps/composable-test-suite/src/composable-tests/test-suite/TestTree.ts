/**
 * The serialisable structure of the enumerated test suite, for the UI to render
 * before anything runs. Groups correspond to test cases; each group lists its
 * tests (enumerated variants) by stable id and display name. Ids are opaque — the
 * UI uses them as keys and in run requests, and never parses them; grouping is
 * driven by this structure, not by the id format.
 */
export interface TestTree {
    groups: TestGroupInfo[];
}

/** One group in the tree: a test case and its enumerated tests. */
export interface TestGroupInfo {
    /** The case's CamelCase identifier (leads the group header). */
    identifier: string;
    /** The case's plain-terms description (shown next to / below the header). */
    description: string;
    /** The group's tests, in enumeration order. */
    tests: TestInfo[];
}

/** One test in the tree: its stable id and display name. */
export interface TestInfo {
    id: string;
    name: string;
}
