//! Equal division of an adjustment over capped opportunities, shared by the
//! horizontal and vertical justification and squeezing passes.

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
