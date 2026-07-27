import { TestCase } from "./test-suite/TestCase.ts";
import { createTestCaseCopyOverrideSurvivesMainChange } from "./cases/caseCopyOverrideSurvivesMainChange.ts";
import { createTestCaseMainEditSyncs } from "./cases/caseMainEditSyncs.ts";
import { createTestCaseRemoteMainCopySyncNested } from "./cases/caseRemoteMainCopySyncNested.ts";
import { createTestCaseVariantSwitchPropagates } from "./cases/caseVariantSwitchPropagates.ts";
import { createTestCaseCopySubheadDeletePreservesSlots } from "./cases/caseCopySubheadDeletePreservesSlots.ts";
import { createTestCaseMainReorderKeepsCopySlots } from "./cases/caseMainReorderKeepsCopySlots.ts";

/**
 * All composable test cases currently defined. A factory (not a constant): each
 * case captures live foundation state and is rebuilt per run, and the runner
 * further rebuilds the configuration per enumerated variant.
 */
export function allCases(): readonly TestCase[] {
    return [
        createTestCaseCopyOverrideSurvivesMainChange(),
        createTestCaseMainEditSyncs(),
        createTestCaseRemoteMainCopySyncNested(),
        createTestCaseVariantSwitchPropagates(),
        createTestCaseCopySubheadDeletePreservesSlots(),
        createTestCaseMainReorderKeepsCopySlots(),
    ];
}
