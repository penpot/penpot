//! Equal division of an adjustment over capped opportunities, shared by the
//! horizontal and vertical justification and squeezing passes.

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
