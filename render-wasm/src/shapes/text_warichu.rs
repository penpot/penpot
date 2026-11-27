//! Warichu (割注): inline notes set in two half-size sub-lines.

use crate::shapes::jlreq::break_allowed_between;

pub const WARICHU_FONT_SCALE: f32 = 0.5;

/// Char index where a warichu run splits into its two sub-lines: the
/// midpoint (first line longer), moved to the nearest split a line may break
/// at, which keeps kinsoku and does not cut a numeral or Western run. At
/// equal distance a forward move wins, pulling the mark up into the first
/// sub-line (jlreq). Falls back to the midpoint.
pub(crate) fn warichu_split_chars(text: &str) -> usize {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mid = n.div_ceil(2);
    let valid = |split: usize| {
        split >= 1 && split < n && break_allowed_between(chars[split - 1], chars[split])
    };
    if valid(mid) {
        return mid;
    }
    for distance in 1..n {
        if valid(mid + distance) {
            return mid + distance;
        }
        if mid > distance && valid(mid - distance) {
            return mid - distance;
        }
    }
    mid
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn warichu_split_balances_and_respects_kinsoku() {
        // Balanced midpoint when nothing forbids it, first line longer.
        assert_eq!(warichu_split_chars("あいうえおか"), 3);
        assert_eq!(warichu_split_chars("あいうえお"), 3);
        // A comma at the midpoint may end the first sub-line...
        assert_eq!(warichu_split_chars("あい、うえ"), 3);
        // ...but must not start the second one: the split moves forward.
        assert_eq!(warichu_split_chars("あいう、えお"), 4);
        // An opening bracket must not end the first sub-line.
        assert_eq!(warichu_split_chars("あい「うえお"), 4);
        // Pathological all-forbidden text keeps the midpoint.
        assert_eq!(warichu_split_chars("、、、、"), 2);
    }

    #[test]
    fn warichu_split_keeps_numeral_and_western_runs_whole() {
        // The midpoint falls inside the year or the word: the nearest
        // break around the run wins, forward at equal distance.
        assert_eq!(warichu_split_chars("西暦1939年"), 6);
        assert_eq!(warichu_split_chars("ああPenpotい"), 8);
    }
}
