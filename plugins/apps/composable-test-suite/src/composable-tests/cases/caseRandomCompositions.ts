import { TestCase } from "../test-suite/TestCase.ts";
import { OpOneOf } from "../operations/OpOneOf.ts";
import { OpRandomSequence } from "../operations/OpRandomSequence.ts";

export function createTestCaseRandomCompositions(): TestCase {
    return new TestCase(
        "RandomFillOpacitySmoke",
        "Smoke coverage of fills and opacity through the Plugin API: two nested components and their copies " +
            "receive seeded fill and opacity edits, resets, duplication, detachment, resizes and turns. Each " +
            "settled step checks only fills and opacity against an independent override model; resizes and " +
            "turns carry no geometry assertions, and no step undoes, swaps, switches variants, edits text or " +
            "checks touched groups. The generated programs of common and their frontend and WASM replays " +
            "cover those.",
        new OpOneOf(new OpRandomSequence(712), new OpRandomSequence(20261022), new OpRandomSequence(1792691))
    );
}
