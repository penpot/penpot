//! Shared JLREQ character classes and pair-rule tables.
//!
//! JLREQ defines thirty layout classes. Classes 20–24 and 28–30 are
//! contextual/virtual classes produced by higher-level inline composites;
//! [`classify`] handles scalar characters and callers assign those virtual
//! classes when constructing reference marks, ruby, grouped numerals,
//! warichu, or tate-chu-yoko.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum JapaneseClass {
    OpeningBracket = 0,
    ClosingBracket,
    Hyphen,
    DividingPunctuation,
    MiddleDot,
    FullStop,
    Comma,
    Inseparable,
    IterationMark,
    ProlongedSoundMark,
    SmallKana,
    PrefixedAbbreviation,
    PostfixedAbbreviation,
    IdeographicSpace,
    Hiragana,
    Katakana,
    MathSymbol,
    MathOperator,
    Ideographic,
    ReferenceMark,
    OrnamentedComplex,
    SimpleRuby,
    JukugoRuby,
    GroupedNumeral,
    UnitSymbol,
    WesternWordSpace,
    Western,
    WarichuOpening,
    WarichuClosing,
    TateChuYoko,
}

impl JapaneseClass {
    pub const COUNT: usize = 30;

    pub const ALL: [Self; Self::COUNT] = [
        Self::OpeningBracket,
        Self::ClosingBracket,
        Self::Hyphen,
        Self::DividingPunctuation,
        Self::MiddleDot,
        Self::FullStop,
        Self::Comma,
        Self::Inseparable,
        Self::IterationMark,
        Self::ProlongedSoundMark,
        Self::SmallKana,
        Self::PrefixedAbbreviation,
        Self::PostfixedAbbreviation,
        Self::IdeographicSpace,
        Self::Hiragana,
        Self::Katakana,
        Self::MathSymbol,
        Self::MathOperator,
        Self::Ideographic,
        Self::ReferenceMark,
        Self::OrnamentedComplex,
        Self::SimpleRuby,
        Self::JukugoRuby,
        Self::GroupedNumeral,
        Self::UnitSymbol,
        Self::WesternWordSpace,
        Self::Western,
        Self::WarichuOpening,
        Self::WarichuClosing,
        Self::TateChuYoko,
    ];

    pub const fn index(self) -> usize {
        self as usize
    }

    pub const fn forbids_line_start(self) -> bool {
        matches!(
            self,
            Self::ClosingBracket
                | Self::Hyphen
                | Self::DividingPunctuation
                | Self::MiddleDot
                | Self::FullStop
                | Self::Comma
                | Self::Inseparable
                | Self::IterationMark
                | Self::ProlongedSoundMark
                | Self::SmallKana
                | Self::PostfixedAbbreviation
                | Self::WarichuClosing
        )
    }

    pub const fn forbids_line_end(self) -> bool {
        matches!(
            self,
            Self::OpeningBracket | Self::PrefixedAbbreviation | Self::WarichuOpening
        )
    }

    pub const fn is_japanese_letter(self) -> bool {
        matches!(self, Self::Hiragana | Self::Katakana | Self::Ideographic)
    }

    pub const fn is_western_run(self) -> bool {
        matches!(
            self,
            Self::GroupedNumeral | Self::UnitSymbol | Self::Western
        )
    }

    pub const fn is_emphasis_prohibited(self) -> bool {
        matches!(
            self,
            Self::OpeningBracket | Self::ClosingBracket | Self::FullStop | Self::Comma
        )
    }

    /// Half-width punctuation whose normal character frame is completed by
    /// half an em after the glyph. Consecutive punctuation may suppress that
    /// appended spacing, but the glyph body itself remains half-width.
    pub const fn is_trailing_aki_punctuation(self) -> bool {
        matches!(self, Self::ClosingBracket | Self::FullStop | Self::Comma)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PairRule {
    /// Preferred extra spacing between the two character frames, in em.
    pub preferred_em: f32,
    /// Smallest spacing allowed during oikomi, in em.
    pub minimum_em: f32,
    /// Largest spacing allowed during oidashi/justification, in em.
    pub maximum_em: f32,
    pub break_allowed: bool,
    /// Whether horizontal SkParagraph needs an inserted WORD JOINER for this
    /// pair. Atomic Western/numeral runs are already protected by its Unicode
    /// breaker, so they remain non-breakable without synthetic characters.
    pub suppress_break_with_joiner: bool,
    /// Lower values are adjusted first; zero means not adjustable.
    pub shrink_priority: u8,
    pub expand_priority: u8,
}

impl PairRule {
    const SOLID: Self = Self {
        preferred_em: 0.0,
        minimum_em: 0.0,
        maximum_em: 0.0,
        break_allowed: true,
        suppress_break_with_joiner: false,
        shrink_priority: 0,
        expand_priority: 0,
    };
}

const fn generated_pair_rules() -> [[PairRule; JapaneseClass::COUNT]; JapaneseClass::COUNT] {
    let mut table = [[PairRule::SOLID; JapaneseClass::COUNT]; JapaneseClass::COUNT];
    let mut before_index = 0;
    while before_index < JapaneseClass::COUNT {
        let before = JapaneseClass::ALL[before_index];
        let mut after_index = 0;
        while after_index < JapaneseClass::COUNT {
            let after = JapaneseClass::ALL[after_index];
            let mut rule = PairRule::SOLID;

            rule.suppress_break_with_joiner =
                before.forbids_line_end() || after.forbids_line_start();
            rule.break_allowed = !rule.suppress_break_with_joiner;
            if before_index == after_index
                && matches!(
                    before,
                    JapaneseClass::Inseparable
                        | JapaneseClass::GroupedNumeral
                        | JapaneseClass::Western
                )
            {
                rule.break_allowed = false;
            }

            if matches!(before, JapaneseClass::WesternWordSpace) {
                rule.preferred_em = 1.0 / 3.0;
                rule.minimum_em = 0.25;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 1;
                rule.expand_priority = 1;
            } else if (before.is_japanese_letter() && after.is_western_run())
                || (before.is_western_run() && after.is_japanese_letter())
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.125;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 5;
                rule.expand_priority = 2;
            } else if matches!(
                (before, after),
                (
                    JapaneseClass::ClosingBracket | JapaneseClass::Comma | JapaneseClass::FullStop,
                    JapaneseClass::TateChuYoko
                ) | (JapaneseClass::TateChuYoko, JapaneseClass::OpeningBracket)
            ) {
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 3;
            } else if matches!(
                (before, after),
                (JapaneseClass::TateChuYoko, JapaneseClass::TateChuYoko)
            ) {
                // Two adjacent cl-30 entries are necessarily separate TCY
                // composites (characters inside one composite are atomic).
                rule.maximum_em = 0.25;
                rule.expand_priority = 3;
            } else if matches!(before, JapaneseClass::DividingPunctuation)
                && !matches!(after, JapaneseClass::ClosingBracket)
            {
                // A sentence-ending question/exclamation mark carries one em
                // after it. Line planning may discard this at the line edge.
                rule.preferred_em = 1.0;
                rule.minimum_em = 0.0;
                rule.maximum_em = 1.0;
                rule.shrink_priority = 2;
            } else if before.is_trailing_aki_punctuation()
                && matches!(after, JapaneseClass::OpeningBracket)
            {
                // Only one half-em is retained between the two half-width
                // glyph bodies, not the sum of both characters' normal aki.
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 4;
            } else if before.is_trailing_aki_punctuation() && after.is_trailing_aki_punctuation() {
                // Consecutive closing punctuation sets solid internally; the
                // last character in the sequence supplies the trailing aki.
            } else if matches!(before, JapaneseClass::OpeningBracket)
                && matches!(after, JapaneseClass::OpeningBracket)
            {
                // Consecutive opening brackets set solid internally; the first
                // character in the sequence supplies the leading aki.
            } else if (before.is_trailing_aki_punctuation()
                && matches!(after, JapaneseClass::MiddleDot))
                || (matches!(before, JapaneseClass::MiddleDot)
                    && matches!(after, JapaneseClass::OpeningBracket))
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.25;
                rule.shrink_priority = 3;
            } else if before.is_trailing_aki_punctuation() {
                rule.preferred_em = 0.5;
                rule.minimum_em = if matches!(before, JapaneseClass::FullStop) {
                    0.5
                } else {
                    0.0
                };
                rule.maximum_em = 0.5;
                rule.shrink_priority = if matches!(before, JapaneseClass::FullStop) {
                    0
                } else {
                    4
                };
            } else if matches!(after, JapaneseClass::OpeningBracket) {
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 4;
            } else if matches!(before, JapaneseClass::MiddleDot)
                || matches!(after, JapaneseClass::MiddleDot)
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.25;
                rule.shrink_priority = 3;
            } else if before.is_japanese_letter() && after.is_japanese_letter() {
                // Solid Japanese text is the general third-stage expansion
                // opportunity. The planner may continue past this quarter-em
                // cap only in JLREQ's final equal-expansion fallback.
                rule.maximum_em = 0.25;
                rule.expand_priority = 3;
            }

            table[before_index][after_index] = rule;
            after_index += 1;
        }
        before_index += 1;
    }
    table
}

pub const PAIR_RULES: [[PairRule; JapaneseClass::COUNT]; JapaneseClass::COUNT] =
    generated_pair_rules();

pub const fn pair_rule(before: JapaneseClass, after: JapaneseClass) -> PairRule {
    PAIR_RULES[before.index()][after.index()]
}

const OPENING_BRACKETS: &str = "（〔［｛〈《「『【〖〘〚‘“";
const CLOSING_BRACKETS: &str = "）〕］｝〉》」』】〗〙〛’”";
const HYPHENS: &str = "‐゠–〜～";
const DIVIDING_PUNCTUATION: &str = "！？‼⁇⁈⁉";
const MIDDLE_DOTS: &str = "・･：；";
const FULL_STOPS: &str = "。．";
const COMMAS: &str = "、，";
const INSEPARABLE: &str = "―…‥〳〴〵";
const ITERATION_MARKS: &str = "々〻ゝゞヽヾ";
const SMALL_KANA: &str = concat!(
    "ぁぃぅぇぉっゃゅょゎゕゖ",
    "ァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ"
);
const PREFIXED_ABBREVIATIONS: &str = "￥¥＄$￡£＃#";
const POSTFIXED_ABBREVIATIONS: &str = "°′″℃￠¢％%‰‱";
const MATH_SYMBOLS: &str = "＝=≠＜<＞>≦≧≤≥∈∋⊆⊇⊂⊃∪∩⊄⊅⊊⊋∉⌅⌆∧∨⇒⇔∥";
const MATH_OPERATORS: &str = "＋+－−-÷×±∓∗∙√∫∬∭∑∏";

pub fn classify(c: char) -> JapaneseClass {
    if OPENING_BRACKETS.contains(c) {
        JapaneseClass::OpeningBracket
    } else if CLOSING_BRACKETS.contains(c) {
        JapaneseClass::ClosingBracket
    } else if HYPHENS.contains(c) {
        JapaneseClass::Hyphen
    } else if DIVIDING_PUNCTUATION.contains(c) {
        JapaneseClass::DividingPunctuation
    } else if MIDDLE_DOTS.contains(c) {
        JapaneseClass::MiddleDot
    } else if FULL_STOPS.contains(c) {
        JapaneseClass::FullStop
    } else if COMMAS.contains(c) {
        JapaneseClass::Comma
    } else if INSEPARABLE.contains(c) {
        JapaneseClass::Inseparable
    } else if ITERATION_MARKS.contains(c) {
        JapaneseClass::IterationMark
    } else if c == 'ー' {
        JapaneseClass::ProlongedSoundMark
    } else if SMALL_KANA.contains(c) {
        JapaneseClass::SmallKana
    } else if PREFIXED_ABBREVIATIONS.contains(c) {
        JapaneseClass::PrefixedAbbreviation
    } else if POSTFIXED_ABBREVIATIONS.contains(c) {
        JapaneseClass::PostfixedAbbreviation
    } else if c == '\u{3000}' {
        JapaneseClass::IdeographicSpace
    } else if MATH_SYMBOLS.contains(c) {
        JapaneseClass::MathSymbol
    } else if MATH_OPERATORS.contains(c) {
        JapaneseClass::MathOperator
    } else if c == ' ' || c == '\t' || c == '\u{00a0}' {
        JapaneseClass::WesternWordSpace
    } else if c.is_ascii_digit() {
        JapaneseClass::GroupedNumeral
    } else if is_hiragana(c) {
        JapaneseClass::Hiragana
    } else if is_katakana(c) {
        JapaneseClass::Katakana
    } else if is_ideographic(c) {
        JapaneseClass::Ideographic
    } else if is_unit_symbol(c) {
        JapaneseClass::UnitSymbol
    } else {
        JapaneseClass::Western
    }
}

fn is_hiragana(c: char) -> bool {
    matches!(u32::from(c), 0x3041..=0x309F)
}

fn is_katakana(c: char) -> bool {
    matches!(u32::from(c), 0x30A0..=0x30FF | 0x31F0..=0x31FF | 0xFF66..=0xFF9D)
}

fn is_ideographic(c: char) -> bool {
    matches!(u32::from(c),
        0x2E80..=0x2FDF
        | 0x31C0..=0x31EF
        | 0x3400..=0x4DBF
        | 0x4E00..=0x9FFF
        | 0xF900..=0xFAFF
        | 0x20000..=0x2FA1F
    ) || matches!(c, '〃' | '仝' | '〆' | '♂' | '♀')
}

fn is_unit_symbol(c: char) -> bool {
    matches!(u32::from(c), 0x2100..=0x214F | 0x3300..=0x33FF)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn class_model_contains_all_thirty_jlreq_classes_in_order() {
        assert_eq!(JapaneseClass::ALL.len(), 30);
        for (index, class) in JapaneseClass::ALL.iter().enumerate() {
            assert_eq!(class.index(), index);
        }
    }

    #[test]
    fn classifies_representative_jlreq_characters() {
        let cases = [
            ('「', JapaneseClass::OpeningBracket),
            ('」', JapaneseClass::ClosingBracket),
            ('〜', JapaneseClass::Hyphen),
            ('！', JapaneseClass::DividingPunctuation),
            ('・', JapaneseClass::MiddleDot),
            ('。', JapaneseClass::FullStop),
            ('、', JapaneseClass::Comma),
            ('…', JapaneseClass::Inseparable),
            ('々', JapaneseClass::IterationMark),
            ('ー', JapaneseClass::ProlongedSoundMark),
            ('ょ', JapaneseClass::SmallKana),
            ('￥', JapaneseClass::PrefixedAbbreviation),
            ('％', JapaneseClass::PostfixedAbbreviation),
            ('\u{3000}', JapaneseClass::IdeographicSpace),
            ('あ', JapaneseClass::Hiragana),
            ('ア', JapaneseClass::Katakana),
            ('≠', JapaneseClass::MathSymbol),
            ('＋', JapaneseClass::MathOperator),
            ('漢', JapaneseClass::Ideographic),
            ('2', JapaneseClass::GroupedNumeral),
            ('㎏', JapaneseClass::UnitSymbol),
            (' ', JapaneseClass::WesternWordSpace),
            ('A', JapaneseClass::Western),
        ];
        for (character, expected) in cases {
            assert_eq!(classify(character), expected, "wrong class for {character}");
        }
    }

    #[test]
    fn generated_rules_cover_every_class_pair() {
        assert_eq!(PAIR_RULES.len(), JapaneseClass::COUNT);
        assert!(PAIR_RULES
            .iter()
            .all(|row| row.len() == JapaneseClass::COUNT));
    }

    #[test]
    fn generated_rules_encode_kinsoku_and_atomic_runs() {
        assert!(
            !pair_rule(JapaneseClass::OpeningBracket, JapaneseClass::Ideographic).break_allowed
        );
        assert!(
            !pair_rule(JapaneseClass::Ideographic, JapaneseClass::ClosingBracket).break_allowed
        );
        assert!(
            !pair_rule(JapaneseClass::GroupedNumeral, JapaneseClass::GroupedNumeral).break_allowed
        );
        assert!(pair_rule(JapaneseClass::Ideographic, JapaneseClass::Ideographic).break_allowed);
    }

    #[test]
    fn generated_rules_encode_script_and_tcy_spacing() {
        let script = pair_rule(JapaneseClass::Ideographic, JapaneseClass::Western);
        assert_eq!(script.preferred_em, 0.25);
        assert_eq!(script.minimum_em, 0.125);
        assert_eq!(script.maximum_em, 0.5);

        let tcy = pair_rule(JapaneseClass::Comma, JapaneseClass::TateChuYoko);
        assert_eq!(tcy.preferred_em, 0.5);
        assert_eq!(
            pair_rule(JapaneseClass::TateChuYoko, JapaneseClass::Comma).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JapaneseClass::OpeningBracket, JapaneseClass::TateChuYoko).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JapaneseClass::TateChuYoko, JapaneseClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JapaneseClass::Ideographic, JapaneseClass::TateChuYoko).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JapaneseClass::TateChuYoko, JapaneseClass::Ideographic).preferred_em,
            0.0
        );
        let adjacent_tcy = pair_rule(JapaneseClass::TateChuYoko, JapaneseClass::TateChuYoko);
        assert_eq!(adjacent_tcy.preferred_em, 0.0);
        assert_eq!(adjacent_tcy.maximum_em, 0.25);
        assert_eq!(adjacent_tcy.expand_priority, 3);
    }

    #[test]
    fn generated_rules_encode_punctuation_sequences() {
        assert_eq!(
            pair_rule(JapaneseClass::Ideographic, JapaneseClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JapaneseClass::ClosingBracket, JapaneseClass::Ideographic).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JapaneseClass::FullStop, JapaneseClass::ClosingBracket).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JapaneseClass::ClosingBracket, JapaneseClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JapaneseClass::ClosingBracket, JapaneseClass::MiddleDot).preferred_em,
            0.25
        );
        assert_eq!(
            pair_rule(JapaneseClass::MiddleDot, JapaneseClass::OpeningBracket).preferred_em,
            0.25
        );
        assert_eq!(
            pair_rule(
                JapaneseClass::DividingPunctuation,
                JapaneseClass::Ideographic
            )
            .preferred_em,
            1.0
        );
        let solid_japanese = pair_rule(JapaneseClass::Ideographic, JapaneseClass::Hiragana);
        assert_eq!(solid_japanese.preferred_em, 0.0);
        assert_eq!(solid_japanese.maximum_em, 0.25);
        assert_eq!(solid_japanese.expand_priority, 3);
        let ruby_adjacency = pair_rule(JapaneseClass::SimpleRuby, JapaneseClass::JukugoRuby);
        assert_eq!(ruby_adjacency.preferred_em, 0.0);
        assert_eq!(ruby_adjacency.maximum_em, 0.0);
        assert_eq!(ruby_adjacency.expand_priority, 0);
    }
}
