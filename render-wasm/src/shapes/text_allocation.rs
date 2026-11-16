//! Equal division of an adjustment over capped opportunities, shared by the
//! horizontal and vertical justification and squeezing passes.

use crate::shapes::jlreq::{pair_rule, JlreqClass};
use crate::shapes::text_policy::TextLayoutPolicy;

/// Amounts below this count as zero when dividing adjustments.
const ALLOCATION_EPSILON: f32 = 0.0001;

/// Divide `amount` equally across capped opportunities, redistributing the
/// remainder whenever one opportunity reaches its cap.
pub(crate) fn capped_equal_allocations(
    capacities: &[(usize, f32)],
    amount: f32,
) -> Vec<(usize, f32)> {
    let mut allocations: Vec<(usize, f32)> = capacities.iter().map(|(i, _)| (*i, 0.0)).collect();
    let mut remaining = amount.max(0.0);
    while remaining > ALLOCATION_EPSILON {
        let active: Vec<usize> = capacities
            .iter()
            .enumerate()
            .filter_map(|(slot, (_, cap))| {
                (allocations[slot].1 + ALLOCATION_EPSILON < *cap).then_some(slot)
            })
            .collect();
        if active.is_empty() {
            break;
        }
        let share = remaining / active.len() as f32;
        let mut used = 0.0;
        for slot in active {
            let cap = capacities[slot].1;
            let delta = share.min(cap - allocations[slot].1);
            allocations[slot].1 += delta;
            used += delta;
        }
        if used <= ALLOCATION_EPSILON {
            break;
        }
        remaining -= used;
    }
    allocations
}

/// Priority and pixel cap of the justification gap between `before` and
/// `after`, at the smaller adjacent font size `em`, or `None` when the gap
/// takes no expansion. Distribution (`distribute_budget`, the line or column
/// budget) fills every gap at one priority, capped only by that budget;
/// otherwise the policy bounds the gap (`TextLayoutPolicy::expansion_em`).
pub(crate) fn bounded_expansion(
    policy: TextLayoutPolicy,
    before: char,
    after: char,
    em: f32,
    distribute_budget: Option<f32>,
) -> Option<(u8, f32)> {
    let (priority, capacity) = match distribute_budget {
        Some(budget) => (1, budget / em.max(0.01)),
        None => policy.expansion_em(before, after),
    };
    (priority > 0).then_some((priority, capacity * em))
}

/// Divide `slack` over bounded justification `opportunities` (index,
/// priority, cap in px): priority 1 first, then priority 2 with what is
/// left, equally within a priority. Returns (index, amount) per allocation.
pub(crate) fn allocate_bounded_expansion(
    opportunities: &[(usize, u8, f32)],
    slack: f32,
) -> Vec<(usize, f32)> {
    let mut remaining = slack;
    let mut allocations = Vec::new();
    for priority in 1..=2 {
        let caps: Vec<_> = opportunities
            .iter()
            .filter(|(_, p, _)| *p == priority)
            .map(|(index, _, cap)| (*index, *cap))
            .collect();
        for (index, amount) in capped_equal_allocations(&caps, remaining) {
            allocations.push((index, amount));
            remaining -= amount;
        }
    }
    allocations
}

/// Priority and pixel cap of the JLREQ justification gap between classes
/// `before` and `after`, which already hold `spacing_em` of pair spacing, at
/// the smaller adjacent font size `em`; `None` when the pair table gives the
/// gap no expansion. Both axes classify their gaps with this.
pub(crate) fn jlreq_expansion_gap(
    before: JlreqClass,
    after: JlreqClass,
    spacing_em: f32,
    em: f32,
) -> Option<(u8, f32)> {
    let rule = pair_rule(before, after);
    (rule.expand_priority > 0).then(|| {
        (
            rule.expand_priority,
            (rule.maximum_em - spacing_em).max(0.0) * em,
        )
    })
}

/// JLREQ ordered expansion (§3.8.4) of one line or column's `slack` over
/// its `gaps` (index, priority, cap in px) from `jlreq_expansion_gap`:
/// stages 1–3 widen gaps in priority order, equally within a stage and up
/// to each cap; a fourth stage spreads what is left evenly over every gap.
/// Returns (index, amount) per allocation; an index may repeat.
pub(crate) fn jlreq_ordered_expansion(gaps: &[(usize, u8, f32)], slack: f32) -> Vec<(usize, f32)> {
    let mut remaining = slack.max(0.0);
    let mut allocations = Vec::new();
    for priority in 1..=3 {
        let caps: Vec<(usize, f32)> = gaps
            .iter()
            .filter(|(_, p, _)| *p == priority)
            .map(|(index, _, cap)| (*index, *cap))
            .collect();
        for (index, amount) in capped_equal_allocations(&caps, remaining) {
            allocations.push((index, amount));
            remaining -= amount;
        }
    }
    if remaining > ALLOCATION_EPSILON && !gaps.is_empty() {
        let extra = remaining / gaps.len() as f32;
        allocations.extend(gaps.iter().map(|(index, _, _)| (*index, extra)));
    }
    allocations
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordered_expansion_fills_each_stage_before_the_next() {
        // Inter-script (stage 2, half an em) before solid kana (stage 3).
        let script = jlreq_expansion_gap(JlreqClass::Hiragana, JlreqClass::Western, 0.25, 20.0);
        let kana = jlreq_expansion_gap(JlreqClass::Hiragana, JlreqClass::Hiragana, 0.0, 20.0);
        assert_eq!(script, Some((2, 5.0)));
        assert_eq!(kana, Some((3, 5.0)));
        assert_eq!(
            jlreq_expansion_gap(JlreqClass::Comma, JlreqClass::Hiragana, 0.5, 20.0),
            None,
            "punctuation aki takes no justification"
        );
        let gaps = [(0, 3, 5.0), (1, 2, 5.0), (2, 3, 5.0)];
        let total = |allocations: &[(usize, f32)], index: usize| -> f32 {
            allocations
                .iter()
                .filter(|(at, _)| *at == index)
                .map(|(_, amount)| amount)
                .sum()
        };
        let small = jlreq_ordered_expansion(&gaps, 4.0);
        assert_eq!((total(&small, 0), total(&small, 1)), (0.0, 4.0));
        let medium = jlreq_ordered_expansion(&gaps, 9.0);
        assert_eq!((total(&medium, 0), total(&medium, 1)), (2.0, 5.0));
        let large = jlreq_ordered_expansion(&gaps, 18.0);
        assert!((total(&large, 0) - 6.0).abs() < 0.001);
        assert!((total(&large, 1) - 6.0).abs() < 0.001);
    }
}
