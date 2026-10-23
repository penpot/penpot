import { TestCase } from "../test-suite/TestCase.ts";
import { OpOneOf } from "../operations/OpOneOf.ts";
import { OpRandomSequence } from "../operations/OpRandomSequence.ts";

export function createTestCaseRandomCompositions(): TestCase {
    return new TestCase(
        "RandomCompositions",
        "Two nested components and their copies receive seeded edits, resets, duplication, detachment " +
            "and transforms. Every settled step must retain authored values and follow untouched main values.",
        new OpOneOf(new OpRandomSequence(712), new OpRandomSequence(20261022), new OpRandomSequence(1792691))
    );
}
