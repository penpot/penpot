// Japanese layout values and rules shared by the horizontal and vertical
// engines: span attributes, annotation scales, emphasis eligibility, the
// warichu split and the ruby overhang, distribution and spreading rules.

use crate::shapes::japanese::{classify, JapaneseClass};
use crate::shapes::kinsoku;

pub const WARICHU_FONT_SCALE: f32 = 0.5;
pub const EMPHASIS_FONT_SCALE: f32 = 0.5;
pub(crate) fn emphasis_char_allowed(character: char) -> bool {
    !character.is_whitespace()
        && !crate::shapes::japanese::classify(character).is_emphasis_prohibited()
}

/// Char index where a warichu run splits into its two sub-lines: the
/// midpoint (first line longer), moved to the nearest split that keeps
/// kinsoku. At equal distance a forward move wins, pulling the mark up into
/// the first sub-line (jlreq). Falls back to the midpoint.
pub(crate) fn warichu_split_chars(text: &str) -> usize {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mid = n.div_ceil(2);
    let valid = |split: usize| {
        split >= 1
            && split < n
            && !kinsoku::forbidden_at_line_start(chars[split])
            && !kinsoku::forbidden_at_line_end(chars[split - 1])
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

/// Room a long reading may overhang one neighbouring character (JLREQ
/// §3.3.8, Fig 135): up to one ruby character, and half the neighbour, over
/// kana or a comma, full stop or middle dot without ruby of their own.
/// Kanji, brackets, another ruby base and a line edge (`None`) get no
/// overhang, nor does any neighbour under `RubyOverhang::None`.
pub(crate) fn ruby_overhang_room(
    policy: RubyOverhang,
    neighbour: Option<char>,
    neighbour_has_ruby: bool,
    neighbour_extent: f32,
    ruby_font_size: f32,
) -> f32 {
    let overhangable = neighbour.is_some_and(|ch| {
        matches!(
            classify(ch),
            JapaneseClass::Hiragana
                | JapaneseClass::Katakana
                | JapaneseClass::SmallKana
                | JapaneseClass::ProlongedSoundMark
                | JapaneseClass::Comma
                | JapaneseClass::FullStop
                | JapaneseClass::MiddleDot
        )
    });
    if policy == RubyOverhang::Auto && overhangable && !neighbour_has_ruby {
        ruby_font_size.min(neighbour_extent / 2.0).max(0.0)
    } else {
        0.0
    }
}

/// How far a reading `overflow` longer than its base hangs over the
/// neighbours before and after it, given their `room`: centred where the room
/// allows, as `distribute_ruby_pieces` places it. Overflow past the room
/// spreads the base instead.
pub(crate) fn ruby_overhangs(overflow: f32, room: (f32, f32)) -> (f32, f32) {
    let (before, after) = room;
    if overflow <= 0.0 {
        return (0.0, 0.0);
    }
    if overflow >= before + after {
        return (before, after);
    }
    let hang_before = (overflow / 2.0)
        .min(before)
        .max((overflow - after).min(before))
        .max(0.0);
    (hang_before, (overflow - hang_before).clamp(0.0, after))
}

/// Gap that spreads a base under a reading longer than itself (JLREQ
/// §3.3.8). What the reading set solid (`ruby_length`) overflows the base
/// by, less the overhang `room`, becomes one gap per base character: a full
/// gap between characters and half a gap at each end.
pub(crate) fn long_ruby_gap(
    ruby_length: f32,
    base_length: f32,
    room: (f32, f32),
    base_chars: usize,
) -> f32 {
    if base_chars == 0 {
        return 0.0;
    }
    let overflow = ruby_length - base_length - room.0 - room.1;
    (overflow / base_chars as f32).max(0.0)
}

/// Pieces a reading is set in (JLREQ §3.3.4): an upright glyph is a piece
/// of its own in a `slot`; a run of Western glyphs is one word at its own
/// advances, which distribution never spreads. `glyphs` holds (western,
/// advance) per glyph. Returns each piece's extent and, per glyph, its piece
/// and its offset inside it.
pub(crate) fn ruby_pieces(glyphs: &[(bool, f32)], slot: f32) -> (Vec<f32>, Vec<(usize, f32)>) {
    let mut extents: Vec<f32> = Vec::new();
    let mut placement = Vec::with_capacity(glyphs.len());
    let mut previous_western = false;
    for &(western, advance) in glyphs {
        if western && previous_western {
            let piece = extents.len() - 1;
            placement.push((piece, extents[piece]));
            extents[piece] += advance;
        } else {
            placement.push((extents.len(), 0.0));
            extents.push(if western { advance } else { slot });
        }
        previous_western = western;
    }
    (extents, placement)
}

/// Flow-axis start of each reading piece of `extents` along the base segment
/// `[seg_top, seg_top + seg_extent)`. Per jlreq:
///
/// - A reading that fits the base is placed by `align`; `SpaceAround` is even
///   distribution (均等割り付け): an equal gap around each piece.
/// - A longer reading packs solid and overhangs the base, centred where the
///   `room` (before, after) of its neighbours allows. Layout grows the base so
///   the overflow fits that room.
pub(crate) fn distribute_ruby_pieces(
    seg_top: f32,
    seg_extent: f32,
    extents: &[f32],
    align: RubyAlign,
    room: (f32, f32),
) -> Vec<f32> {
    let count = extents.len();
    if count == 0 {
        return Vec::new();
    }
    let line: f32 = extents.iter().sum();
    let packed = |start: f32, gap: f32| -> Vec<f32> {
        let mut cursor = start;
        extents
            .iter()
            .map(|extent| {
                let top = cursor;
                cursor += extent + gap;
                top
            })
            .collect()
    };
    if line <= seg_extent {
        let free = seg_extent - line;
        match align {
            RubyAlign::SpaceAround => {
                let gap = free / count as f32;
                packed(seg_top + gap / 2.0, gap)
            }
            RubyAlign::Center => packed(seg_top + free / 2.0, 0.0),
            RubyAlign::Start => packed(seg_top, 0.0),
            RubyAlign::SpaceBetween if count > 1 => packed(seg_top, free / (count - 1) as f32),
            RubyAlign::SpaceBetween => packed(seg_top + free / 2.0, 0.0),
        }
    } else {
        let overflow = line - seg_extent;
        let (before, after) = room;
        let overhang = (overflow / 2.0)
            .min(before)
            .max((overflow - after).min(before));
        packed(seg_top - overhang.max(0.0), 0.0)
    }
}

/// Divide `amount` equally across capped opportunities, redistributing the
/// remainder whenever one opportunity reaches its cap.
/// Amounts below this count as zero when dividing adjustments.
const ALLOCATION_EPSILON: f32 = 0.0001;

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

/// Block flow direction of a paragraph. Horizontal uses skparagraph;
/// vertical-rl uses the custom vertical pass: columns top to bottom,
/// advancing right to left.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum WritingMode {
    #[default]
    HorizontalTb,
    VerticalRl,
}

/// How a line slightly too long for its box is fitted (JLREQ §3.8.1; Adobe's
/// Kinsoku Type). Squeezing reduces punctuation aki to keep the next
/// character; pushing out moves characters to the next line. Alignment then
/// decides what happens to the space a line leaves over.
// The variants are Adobe's Kinsoku Type values and the JS keys.
#[allow(clippy::enum_variant_names)]
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum LineAdjustment {
    /// Squeeze first; push out when the aki cannot absorb the overflow.
    #[default]
    PushInFirst,
    /// Push out first; squeeze only when kinsoku leaves no other break.
    PushOutFirst,
    /// Never squeeze.
    PushOutOnly,
}

/// Glyph orientation inside vertical flow: `Mixed` rotates non-CJK runs
/// sideways, `Upright` keeps every character upright. Ignored in
/// horizontal writing.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextOrientation {
    #[default]
    Mixed,
    Upright,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextCombineUpright {
    #[default]
    None,
    All,
    /// Runs of 2-4 ASCII or full-width digits combine into one upright cell.
    Digits,
    /// Like `Digits`, for runs of exactly 2 (CSS `digits 2`).
    Digits2,
    /// Like `Digits`, for runs of 2-3.
    Digits3,
}

impl TextCombineUpright {
    /// Longest digit run that combines, when digits mode is active.
    pub fn digits_max(self) -> Option<usize> {
        match self {
            TextCombineUpright::Digits => Some(4),
            TextCombineUpright::Digits2 => Some(2),
            TextCombineUpright::Digits3 => Some(3),
            _ => None,
        }
    }
}

/// Emphasis mark (圏点 / bouten) applied per span, mirroring CSS
/// `text-emphasis-style`. The mark is drawn above each eligible horizontal
/// base character or to the right of its vertical column.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum TextEmphasis {
    #[default]
    None,
    FilledDot,
    OpenDot,
    FilledCircle,
    OpenCircle,
    FilledSesame,
    OpenSesame,
}

impl TextEmphasis {
    pub fn is_none(self) -> bool {
        matches!(self, TextEmphasis::None)
    }

    /// The glyph drawn as the emphasis mark, following the CSS
    /// `text-emphasis-style` character mapping.
    pub fn mark_char(self) -> Option<char> {
        match self {
            TextEmphasis::None => None,
            TextEmphasis::FilledDot => Some('•'),
            TextEmphasis::OpenDot => Some('◦'),
            TextEmphasis::FilledCircle => Some('●'),
            TextEmphasis::OpenCircle => Some('○'),
            TextEmphasis::FilledSesame => Some('﹅'),
            TextEmphasis::OpenSesame => Some('﹆'),
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum FontFeatures {
    #[default]
    None,
    Palt,
    Vpal,
}

/// Whether ruby and emphasis on the same side stack. `Auto` places emphasis
/// outside over-side ruby and reserves room for both; `None` lets them share
/// one layer.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum AnnotationClearance {
    #[default]
    None,
    Auto,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubySize {
    #[default]
    Half,
    Third,
    Quarter,
}

impl RubySize {
    pub fn scale(self) -> f32 {
        match self {
            Self::Half => 0.5,
            Self::Third => 1.0 / 3.0,
            Self::Quarter => 0.25,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyAlign {
    #[default]
    SpaceAround,
    Center,
    Start,
    SpaceBetween,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubyOverhang {
    #[default]
    Auto,
    None,
}

#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub enum RubySide {
    #[default]
    Over,
    Under,
}

impl AnnotationClearance {
    pub fn is_auto(self) -> bool {
        matches!(self, AnnotationClearance::Auto)
    }
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
    fn emphasis_excludes_whitespace_and_japanese_punctuation() {
        for character in " \t\n、。，．「」『』（）［］【】〔〕〈〉《》‘’“”".chars()
        {
            assert!(
                !emphasis_char_allowed(character),
                "emphasis must skip {character:?}"
            );
        }
        for character in "漢あA1・！？".chars() {
            assert!(
                emphasis_char_allowed(character),
                "emphasis should mark {character:?}"
            );
        }
    }

    #[test]
    fn ruby_shorter_than_base_distributes_evenly() {
        // A ruby line of 2 x 50 equals the base extent (100): one glyph per
        // slot, with no offset.
        let tops =
            distribute_ruby_pieces(0.0, 100.0, &[50.0; 2], RubyAlign::SpaceAround, (50.0, 50.0));
        assert_eq!(tops.len(), 2);
        assert!(
            (tops[0] - 0.0).abs() < 0.001,
            "first ruby glyph at slot start"
        );
        assert!(
            (tops[1] - 50.0).abs() < 0.001,
            "second ruby glyph one slot down"
        );

        // A wide base (extent 200) spreads 2 glyphs of advance 50 into slots
        // of 100.
        let spread =
            distribute_ruby_pieces(0.0, 200.0, &[50.0; 2], RubyAlign::SpaceAround, (50.0, 50.0));
        assert!(
            (spread[0] - 25.0).abs() < 0.001,
            "ruby glyph centred in its slot"
        );
        assert!(
            (spread[1] - spread[0] - 100.0).abs() < 0.001,
            "even distribution keeps a full slot between glyphs, not the advance"
        );
    }

    #[test]
    fn ruby_longer_than_base_overhangs_symmetrically() {
        // A ruby line of 80 over a 40 base centres on the base, overhanging
        // each end by 20 (the one-em cap).
        let tops =
            distribute_ruby_pieces(0.0, 40.0, &[20.0; 4], RubyAlign::SpaceAround, (50.0, 50.0));
        assert_eq!(tops.len(), 4);
        assert!(tops[0] < 0.0, "long ruby overhangs above the base top");
        let block_center = (tops[0] + tops[3] + 20.0) / 2.0;
        assert!(
            (block_center - 20.0).abs() < 0.001,
            "the ruby block stays centred on the base"
        );
    }

    #[test]
    fn ruby_alignment_modes_control_short_annotation_distribution() {
        assert_eq!(
            distribute_ruby_pieces(0.0, 20.0, &[4.0; 2], RubyAlign::SpaceAround, (4.0, 4.0),),
            vec![3.0, 13.0]
        );
        assert_eq!(
            distribute_ruby_pieces(0.0, 20.0, &[4.0; 2], RubyAlign::Center, (4.0, 4.0)),
            vec![6.0, 10.0]
        );
        assert_eq!(
            distribute_ruby_pieces(0.0, 20.0, &[4.0; 2], RubyAlign::Start, (4.0, 4.0)),
            vec![0.0, 4.0]
        );
        assert_eq!(
            distribute_ruby_pieces(0.0, 20.0, &[4.0; 2], RubyAlign::SpaceBetween, (4.0, 4.0),),
            vec![0.0, 16.0]
        );
    }

    #[test]
    fn ruby_overhang_none_keeps_long_annotation_at_base_start() {
        let automatic = distribute_ruby_pieces(0.0, 10.0, &[4.0; 4], RubyAlign::Center, (4.0, 4.0));
        let constrained =
            distribute_ruby_pieces(0.0, 10.0, &[4.0; 4], RubyAlign::Center, (0.0, 0.0));

        assert_eq!(automatic, vec![-3.0, 1.0, 5.0, 9.0]);
        assert_eq!(constrained, vec![0.0, 4.0, 8.0, 12.0]);
    }

    #[test]
    fn long_ruby_gap_spreads_the_overflow_left_after_the_room() {
        // 60 of reading over 40 of base, 4 of room on each side: 12 left
        // over two characters, one gap each, half of it at each end.
        assert_eq!(long_ruby_gap(60.0, 40.0, (4.0, 4.0), 2), 6.0);
        assert_eq!(long_ruby_gap(20.0, 40.0, (0.0, 0.0), 2), 0.0);
        assert_eq!(long_ruby_gap(60.0, 40.0, (0.0, 0.0), 0), 0.0);
    }

    #[test]
    fn ruby_overhangs_follow_the_distribution() {
        assert_eq!(ruby_overhangs(0.0, (5.0, 5.0)), (0.0, 0.0));
        assert_eq!(ruby_overhangs(6.0, (5.0, 5.0)), (3.0, 3.0));
        assert_eq!(ruby_overhangs(6.0, (5.0, 0.0)), (5.0, 0.0));
        assert_eq!(ruby_overhangs(6.0, (0.0, 5.0)), (0.0, 5.0));
        assert_eq!(ruby_overhangs(20.0, (5.0, 5.0)), (5.0, 5.0));
    }

    #[test]
    fn ruby_pieces_keep_a_western_word_whole() {
        // エディ + "tor": three upright slots, then one word at its advances.
        let glyphs = [
            (false, 9.0),
            (false, 9.0),
            (true, 4.0),
            (true, 5.0),
            (true, 3.0),
        ];
        let (extents, placement) = ruby_pieces(&glyphs, 10.0);
        assert_eq!(extents, vec![10.0, 10.0, 12.0]);
        assert_eq!(
            placement,
            vec![(0, 0.0), (1, 0.0), (2, 0.0), (2, 4.0), (2, 9.0)]
        );
    }

    #[test]
    fn distributing_a_word_never_spreads_its_letters() {
        // JLREQ Fig 112: "editor" over 編集者 is one piece centred on the base.
        let tops = distribute_ruby_pieces(0.0, 60.0, &[30.0], RubyAlign::SpaceAround, (0.0, 0.0));
        assert_eq!(tops, vec![15.0]);
        let tops =
            distribute_ruby_pieces(0.0, 60.0, &[10.0, 30.0], RubyAlign::SpaceAround, (0.0, 0.0));
        assert_eq!(tops, vec![5.0, 25.0]);
    }

    #[test]
    fn ruby_overhang_room_allows_kana_and_punctuation_without_ruby() {
        assert_eq!(
            ruby_overhang_room(RubyOverhang::Auto, Some('か'), false, 20.0, 10.0),
            10.0
        );
        // JLREQ Fig 135: 偏、冠、脚 hangs かんむり over the commas; Fig 136:
        // never over a bracket.
        for ch in ['、', '。', '・'] {
            assert_eq!(
                ruby_overhang_room(RubyOverhang::Auto, Some(ch), false, 20.0, 10.0),
                10.0
            );
        }
        for ch in ['「', '」'] {
            assert_eq!(
                ruby_overhang_room(RubyOverhang::Auto, Some(ch), false, 20.0, 10.0),
                0.0
            );
        }
        assert_eq!(
            ruby_overhang_room(RubyOverhang::Auto, Some('か'), false, 12.0, 10.0),
            6.0
        );
        assert_eq!(
            ruby_overhang_room(RubyOverhang::Auto, Some('漢'), false, 20.0, 10.0),
            0.0
        );
        assert_eq!(
            ruby_overhang_room(RubyOverhang::Auto, Some('か'), true, 20.0, 10.0),
            0.0
        );
        assert_eq!(
            ruby_overhang_room(RubyOverhang::Auto, None, false, 20.0, 10.0),
            0.0
        );
        assert_eq!(
            ruby_overhang_room(RubyOverhang::None, Some('か'), false, 20.0, 10.0),
            0.0
        );
    }
}
