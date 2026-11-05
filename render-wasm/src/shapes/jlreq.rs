//! JLREQ (Japanese) character classes, pair-rule tables and kinsoku rules,
//! shared by the horizontal and vertical engines.
//!
//! JLREQ defines thirty layout classes. Classes 20–24 and 28–30 are
//! virtual classes for inline composites: [`classify`] handles single
//! characters, and callers assign the virtual classes when building
//! reference marks, ruby, grouped numerals, warichu, or tate-chu-yoko.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum JlreqClass {
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

impl JlreqClass {
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
                | Self::IterationMark
                | Self::ProlongedSoundMark
                | Self::SmallKana
                | Self::WarichuClosing
        )
    }

    pub const fn forbids_line_end(self) -> bool {
        matches!(self, Self::OpeningBracket | Self::WarichuOpening)
    }

    pub const fn is_japanese_letter(self) -> bool {
        matches!(self, Self::Hiragana | Self::Katakana | Self::Ideographic)
    }

    /// Kana, kanji and the marks that read as part of a word: small kana,
    /// the prolonged sound mark and iteration marks.
    pub const fn is_japanese_text(self) -> bool {
        self.is_japanese_letter()
            || matches!(
                self,
                Self::SmallKana | Self::ProlongedSoundMark | Self::IterationMark
            )
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

    /// Half-width punctuation followed by a half-em of aki that completes
    /// its character frame. Consecutive punctuation may drop that aki; the
    /// glyph body stays half-width.
    pub const fn is_trailing_aki_punctuation(self) -> bool {
        matches!(self, Self::ClosingBracket | Self::FullStop | Self::Comma)
    }

    /// Aki (in em) a full-width font builds into the glyph before its ink.
    pub const fn embedded_leading_aki_em(self) -> f32 {
        match self {
            Self::OpeningBracket => 0.5,
            Self::MiddleDot => 0.25,
            _ => 0.0,
        }
    }

    /// Aki (in em) a full-width font builds into the glyph after its ink.
    pub const fn embedded_trailing_aki_em(self) -> f32 {
        match self {
            Self::ClosingBracket | Self::FullStop | Self::Comma => 0.5,
            Self::MiddleDot => 0.25,
            _ => 0.0,
        }
    }
}

/// Whether the half-em aki between `before` and `after` goes (JLREQ §3.1.4):
/// closing sequences set solid, closing→opening keeps one half-em, opening
/// sequences set solid after the first bracket. Returns (shed the trailing
/// aki of `before`, shed the leading aki of `after`); a closing mark owns the
/// reduction, so both halves never go.
pub fn shed_pair_aki(before: JlreqClass, after: JlreqClass) -> (bool, bool) {
    let preferred = pair_rule(before, after).preferred_em + f32::EPSILON;
    if before.is_trailing_aki_punctuation() {
        (0.5 + after.embedded_leading_aki_em() > preferred, false)
    } else if after == JlreqClass::OpeningBracket {
        (false, before.embedded_trailing_aki_em() + 0.5 > preferred)
    } else {
        (false, false)
    }
}

/// Which built-in half-em aki of a character goes.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AkiShed {
    /// The aki after a closing mark.
    Trailing,
    /// The aki before an opening bracket.
    Leading,
}

/// Class a character takes for punctuation aki, or `None` when its built-in
/// aki is unknown: punctuation set proportionally (`proportional`, from
/// `palt` or `vpal` in the flow direction) and curly quotes, which are
/// full-width in some fonts and proportional in others. A ruby base counts
/// as simple ruby (cl-22).
pub fn aki_class(ch: char, ruby_base: bool, proportional: bool) -> Option<JlreqClass> {
    if ruby_base {
        return Some(JlreqClass::SimpleRuby);
    }
    let curly_quote = matches!(ch, '‘' | '’' | '“' | '”');
    (!proportional && !curly_quote).then(|| classify(ch))
}

/// The half-em aki that go in a run of characters classed by `aki_class`
/// (JLREQ §3.1.4, see `shed_pair_aki`), as (character index, which aki).
/// A character of unknown aki (`None`) sheds nothing and makes its
/// neighbours keep theirs.
pub fn punctuation_aki_sheds(classes: &[Option<JlreqClass>]) -> Vec<(usize, AkiShed)> {
    let mut sheds = Vec::new();
    for (index, pair) in classes.windows(2).enumerate() {
        let [Some(before), Some(after)] = *pair else {
            continue;
        };
        match shed_pair_aki(before, after) {
            (true, _) => sheds.push((index, AkiShed::Trailing)),
            (_, true) => sheds.push((index + 1, AkiShed::Leading)),
            _ => {}
        }
    }
    sheds
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
    /// Whether horizontal SkParagraph needs a WORD JOINER to block a break
    /// in this pair. Its Unicode breaker already keeps Western/numeral runs
    /// whole.
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

const fn generated_pair_rules() -> [[PairRule; JlreqClass::COUNT]; JlreqClass::COUNT] {
    let mut table = [[PairRule::SOLID; JlreqClass::COUNT]; JlreqClass::COUNT];
    let mut before_index = 0;
    while before_index < JlreqClass::COUNT {
        let before = JlreqClass::ALL[before_index];
        let mut after_index = 0;
        while after_index < JlreqClass::COUNT {
            let after = JlreqClass::ALL[after_index];
            let mut rule = PairRule::SOLID;

            rule.suppress_break_with_joiner =
                before.forbids_line_end() || after.forbids_line_start();
            rule.break_allowed = !rule.suppress_break_with_joiner;
            if before_index == after_index
                && matches!(before, JlreqClass::GroupedNumeral | JlreqClass::Western)
            {
                rule.break_allowed = false;
            }

            if matches!(before, JlreqClass::WesternWordSpace) {
                rule.preferred_em = 1.0 / 3.0;
                rule.minimum_em = 0.25;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 1;
                rule.expand_priority = 1;
            } else if (before.is_japanese_text() && after.is_western_run())
                || (before.is_western_run() && after.is_japanese_text())
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.125;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 5;
                rule.expand_priority = 2;
            } else if matches!(
                (before, after),
                (
                    JlreqClass::ClosingBracket | JlreqClass::Comma | JlreqClass::FullStop,
                    JlreqClass::TateChuYoko
                ) | (JlreqClass::TateChuYoko, JlreqClass::OpeningBracket)
            ) {
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 3;
            } else if matches!(
                (before, after),
                (JlreqClass::TateChuYoko, JlreqClass::TateChuYoko)
            ) {
                // Adjacent cl-30 entries are always separate TCY composites
                // (characters inside one composite are atomic).
                rule.maximum_em = 0.25;
                rule.expand_priority = 3;
            } else if matches!(before, JlreqClass::DividingPunctuation)
                && !matches!(
                    after,
                    JlreqClass::ClosingBracket
                        | JlreqClass::DividingPunctuation
                        | JlreqClass::IdeographicSpace
                        | JlreqClass::WesternWordSpace
                        | JlreqClass::FullStop
                        | JlreqClass::Comma
                        | JlreqClass::MiddleDot
                )
            {
                // A sentence-ending question/exclamation mark carries one em
                // after it, unless punctuation or a typed space follows. Line
                // planning may drop it at the line edge.
                rule.preferred_em = 1.0;
                rule.minimum_em = 0.0;
                rule.maximum_em = 1.0;
                rule.shrink_priority = 2;
            } else if before.is_trailing_aki_punctuation()
                && matches!(after, JlreqClass::OpeningBracket)
            {
                // Keep one half-em between the two half-width glyph bodies,
                // not the sum of both characters' aki.
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 4;
            } else if before.is_trailing_aki_punctuation() && after.is_trailing_aki_punctuation() {
                // Consecutive closing punctuation sets solid internally; the
                // last character in the sequence supplies the trailing aki.
            } else if matches!(before, JlreqClass::OpeningBracket)
                && matches!(after, JlreqClass::OpeningBracket)
            {
                // Consecutive opening brackets set solid internally; the first
                // character in the sequence supplies the leading aki.
            } else if (before.is_trailing_aki_punctuation()
                && matches!(after, JlreqClass::MiddleDot))
                || (matches!(before, JlreqClass::MiddleDot)
                    && matches!(after, JlreqClass::OpeningBracket))
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.25;
                rule.shrink_priority = 3;
            } else if before.is_trailing_aki_punctuation() {
                rule.preferred_em = 0.5;
                rule.minimum_em = if matches!(before, JlreqClass::FullStop) {
                    0.5
                } else {
                    0.0
                };
                rule.maximum_em = 0.5;
                rule.shrink_priority = if matches!(before, JlreqClass::FullStop) {
                    0
                } else {
                    4
                };
            } else if matches!(after, JlreqClass::OpeningBracket) {
                rule.preferred_em = 0.5;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.5;
                rule.shrink_priority = 4;
            } else if matches!(before, JlreqClass::MiddleDot)
                || matches!(after, JlreqClass::MiddleDot)
            {
                rule.preferred_em = 0.25;
                rule.minimum_em = 0.0;
                rule.maximum_em = 0.25;
                rule.shrink_priority = 3;
            } else if before.is_japanese_letter() && after.is_japanese_letter() {
                // Solid Japanese text is the general third-stage expansion
                // point. The planner passes this quarter-em cap only in
                // JLREQ's final equal-expansion fallback.
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

pub const PAIR_RULES: [[PairRule; JlreqClass::COUNT]; JlreqClass::COUNT] = generated_pair_rules();

pub const fn pair_rule(before: JlreqClass, after: JlreqClass) -> PairRule {
    PAIR_RULES[before.index()][after.index()]
}

const OPENING_BRACKETS: &str = "（〔［｛〈《「『【〖〘〚‘“〝«⦅｟｢";
const CLOSING_BRACKETS: &str = "）〕］｝〉》」』】〗〙〛’”〟〞»⦆｠｣";
const HYPHENS: &str = "‐゠–〜～";
const DIVIDING_PUNCTUATION: &str = "！？‼⁇⁈⁉";
const MIDDLE_DOTS: &str = "・･：；";
const FULL_STOPS: &str = "。．｡";
const COMMAS: &str = "、，､";
const INSEPARABLE: &str = "—―…‥〳〴〵";
const ITERATION_MARKS: &str = "々〻ゝゞヽヾ";
const SMALL_KANA: &str = concat!(
    "ぁぃぅぇぉっゃゅょゎゕゖ",
    "ァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ",
    "ｧｨｩｪｫｬｭｮｯ"
);
const PREFIXED_ABBREVIATIONS: &str = "￥¥＄$￡£＃#€";
const POSTFIXED_ABBREVIATIONS: &str = concat!("°′″℃￠¢％%‰‱ℓ", "㌃㌍㌔㌘㌢㌣㌦㌧㌫㌶㌻㍉㍊㍍㍑㍗");
const MATH_SYMBOLS: &str = "＝=≠≒≃≅≈≡≢＜<＞>≦≧≤≥≪≫≶≷⋚⋛∈∋⊆⊇⊂⊃∪∩⊄⊅⊊⊋∉⌅⌆∧∨⇒⇔↔∥∦∽∝⊥⊕⊗";
const MATH_OPERATORS: &str = "＋+－−-÷×±∓∗∙√∫∬∭∑∏";

pub fn classify(c: char) -> JlreqClass {
    // Common letters skip the table scans.
    match c {
        '\u{4E00}'..='\u{9FFF}' | '\u{3400}'..='\u{4DBF}' => JlreqClass::Ideographic,
        'a'..='z' | 'A'..='Z' => JlreqClass::Western,
        _ => classify_by_tables(c),
    }
}

fn classify_by_tables(c: char) -> JlreqClass {
    if OPENING_BRACKETS.contains(c) {
        JlreqClass::OpeningBracket
    } else if CLOSING_BRACKETS.contains(c) {
        JlreqClass::ClosingBracket
    } else if HYPHENS.contains(c) {
        JlreqClass::Hyphen
    } else if DIVIDING_PUNCTUATION.contains(c) {
        JlreqClass::DividingPunctuation
    } else if MIDDLE_DOTS.contains(c) {
        JlreqClass::MiddleDot
    } else if FULL_STOPS.contains(c) {
        JlreqClass::FullStop
    } else if COMMAS.contains(c) {
        JlreqClass::Comma
    } else if INSEPARABLE.contains(c) {
        JlreqClass::Inseparable
    } else if ITERATION_MARKS.contains(c) {
        JlreqClass::IterationMark
    } else if c == 'ー' || c == 'ｰ' {
        JlreqClass::ProlongedSoundMark
    } else if SMALL_KANA.contains(c) {
        JlreqClass::SmallKana
    } else if PREFIXED_ABBREVIATIONS.contains(c) {
        JlreqClass::PrefixedAbbreviation
    } else if POSTFIXED_ABBREVIATIONS.contains(c) {
        JlreqClass::PostfixedAbbreviation
    } else if c == '\u{3000}' {
        JlreqClass::IdeographicSpace
    } else if MATH_SYMBOLS.contains(c) {
        JlreqClass::MathSymbol
    } else if MATH_OPERATORS.contains(c) {
        JlreqClass::MathOperator
    } else if c == ' ' || c == '\t' || c == '\u{00a0}' {
        JlreqClass::WesternWordSpace
    } else if c.is_ascii_digit() {
        JlreqClass::GroupedNumeral
    } else if is_hiragana(c) {
        JlreqClass::Hiragana
    } else if is_katakana(c) {
        JlreqClass::Katakana
    } else if is_ideographic(c) {
        JlreqClass::Ideographic
    } else if is_squared_unit(c) {
        JlreqClass::PostfixedAbbreviation
    } else if is_ideographic_symbol(c) {
        JlreqClass::Ideographic
    } else {
        JlreqClass::Western
    }
}

/// Digits that group with an adjacent prefixed or postfixed abbreviation.
fn is_numeral(c: char) -> bool {
    c.is_ascii_digit() || matches!(u32::from(c), 0xFF10..=0xFF19)
}

/// Character pairs kept on one line beyond the class table: two identical
/// inseparable marks (`——`, `……`), an abbreviation with its numeral
/// (`¥100`, `60㎏`, `１００％`), per JLREQ §3.1.10, and digits of one
/// number, full-width ones included (`２０２６`), which JLREQ would let break
/// as ideographs. ASCII digit pairs are already grouped numerals.
pub fn keeps_together(before: char, after: char) -> bool {
    keeps_together_classified((before, classify(before)), (after, classify(after)))
}

/// `keeps_together` for characters already paired with their classes.
pub fn keeps_together_classified(
    (before, before_class): (char, JlreqClass),
    (after, after_class): (char, JlreqClass),
) -> bool {
    (before == after && before_class == JlreqClass::Inseparable)
        || (is_numeral(before)
            && is_numeral(after)
            && !(before.is_ascii_digit() && after.is_ascii_digit()))
        || (is_numeral(before) && after_class == JlreqClass::PostfixedAbbreviation)
        || (before_class == JlreqClass::PrefixedAbbreviation && is_numeral(after))
}

/// Kinsoku shori: a character that may not start a line.
pub fn forbidden_at_line_start(c: char) -> bool {
    classify(c).forbids_line_start()
}

/// Kinsoku shori: a character that may not end a line.
pub fn forbidden_at_line_end(c: char) -> bool {
    classify(c).forbids_line_end()
}

/// Whether a line may break between two adjacent characters: the pair table
/// (kinsoku, Western and numeral runs) plus `keeps_together`.
pub fn break_allowed_between(before: char, after: char) -> bool {
    pair_rule(classify(before), classify(after)).break_allowed && !keeps_together(before, after)
}

/// True for CJK text characters: kana, Han ideographs, and the CJK symbols
/// and punctuation, katakana extension and half/full-width form blocks.
/// Hangul is not included.
pub fn is_cjk_text_char(c: char) -> bool {
    classify(c).is_japanese_letter()
        || matches!(u32::from(c), 0x3000..=0x30FF | 0x31F0..=0x31FF | 0xFF00..=0xFFEF)
}

fn is_hiragana(c: char) -> bool {
    matches!(u32::from(c), 0x3041..=0x309F)
}

fn is_katakana(c: char) -> bool {
    matches!(u32::from(c), 0x30A0..=0x30FF | 0x31F0..=0x31FF | 0xFF66..=0xFF9F)
}

fn is_ideographic(c: char) -> bool {
    matches!(u32::from(c),
        0x2E80..=0x2FDF
        | 0x31C0..=0x31EF
        | 0x3400..=0x4DBF
        | 0x4E00..=0x9FFF
        | 0xF900..=0xFAFF
        | 0x20000..=0x2FA1F
        | 0x30000..=0x323AF
    ) || matches!(c, '〃' | '仝' | '〆' | '♂' | '♀')
}

/// Squared Latin unit abbreviations (㎏, ㎡, ㏄, …), cl-13 like ％. ㏍ is a
/// company mark, cl-19.
fn is_squared_unit(c: char) -> bool {
    matches!(u32::from(c), 0x3380..=0x33DF) && c != '㏍'
}

/// Symbols JLREQ sets as full-width ideographic characters (cl-19): the
/// full-width forms, CJK symbols, circled and parenthesized characters,
/// Roman numerals, arrows, shapes and squared words. Latin-1 symbols such
/// as © or † stay Western, as they also appear in Latin text.
fn is_ideographic_symbol(c: char) -> bool {
    matches!(u32::from(c),
        0xFF01..=0xFF5E
        | 0x2160..=0x217F
        | 0x2190..=0x21FF
        | 0x2460..=0x24FF
        | 0x25A0..=0x25FF
        | 0x2600..=0x26FF
        | 0x2776..=0x277F
        | 0x3000..=0x303F
        | 0x3200..=0x33FF
    ) || matches!(
        c,
        '※' | '⁂' | '⁑' | '℡' | '✓' | '❖' | '⤴' | '⤵' | '⦿' | 'ゟ' | 'ヿ'
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classes_forbidden_at_start() {
        for c in "、。」』）ーっゃァッ々・！？".chars() {
            assert!(forbidden_at_line_start(c), "expected start-forbidden: {c}");
        }
        for c in "あ漢A1「（ ".chars() {
            assert!(!forbidden_at_line_start(c), "not start-forbidden: {c}");
        }
    }

    #[test]
    fn classes_forbidden_at_end() {
        for c in "「『（［【〈《".chars() {
            assert!(forbidden_at_line_end(c), "expected end-forbidden: {c}");
        }
        for c in "あ漢A1」）。".chars() {
            assert!(!forbidden_at_line_end(c), "not end-forbidden: {c}");
        }
    }

    #[test]
    fn aki_class_leaves_out_curly_quotes_and_proportional_punctuation() {
        assert_eq!(aki_class('“', false, false), None);
        assert_eq!(aki_class('」', false, true), None);
        assert_eq!(
            aki_class('」', false, false),
            Some(JlreqClass::ClosingBracket)
        );
        assert_eq!(aki_class('」', true, false), Some(JlreqClass::SimpleRuby));
    }

    #[test]
    fn punctuation_aki_sheds_the_trailing_aki_of_a_closing_mark() {
        use JlreqClass::*;
        let classes = [Some(ClosingBracket), Some(OpeningBracket)];
        assert_eq!(
            punctuation_aki_sheds(&classes),
            vec![(0, AkiShed::Trailing)]
        );
    }

    #[test]
    fn punctuation_aki_sheds_the_leading_aki_of_a_second_opening_bracket() {
        use JlreqClass::*;
        let classes = [Some(OpeningBracket), Some(OpeningBracket)];
        assert_eq!(punctuation_aki_sheds(&classes), vec![(1, AkiShed::Leading)]);
    }

    #[test]
    fn punctuation_aki_sheds_nothing_next_to_an_unknown_aki() {
        use JlreqClass::*;
        let classes = [Some(ClosingBracket), None, Some(OpeningBracket)];
        assert!(punctuation_aki_sheds(&classes).is_empty());
    }

    #[test]
    fn class_model_contains_all_thirty_jlreq_classes_in_order() {
        assert_eq!(JlreqClass::ALL.len(), 30);
        for (index, class) in JlreqClass::ALL.iter().enumerate() {
            assert_eq!(class.index(), index);
        }
    }

    #[test]
    fn classifies_representative_jlreq_characters() {
        let cases = [
            ('「', JlreqClass::OpeningBracket),
            ('」', JlreqClass::ClosingBracket),
            ('〜', JlreqClass::Hyphen),
            ('！', JlreqClass::DividingPunctuation),
            ('・', JlreqClass::MiddleDot),
            ('。', JlreqClass::FullStop),
            ('、', JlreqClass::Comma),
            ('…', JlreqClass::Inseparable),
            ('々', JlreqClass::IterationMark),
            ('ー', JlreqClass::ProlongedSoundMark),
            ('ょ', JlreqClass::SmallKana),
            ('￥', JlreqClass::PrefixedAbbreviation),
            ('％', JlreqClass::PostfixedAbbreviation),
            ('\u{3000}', JlreqClass::IdeographicSpace),
            ('あ', JlreqClass::Hiragana),
            ('ア', JlreqClass::Katakana),
            ('≠', JlreqClass::MathSymbol),
            ('＋', JlreqClass::MathOperator),
            ('漢', JlreqClass::Ideographic),
            ('2', JlreqClass::GroupedNumeral),
            ('㎏', JlreqClass::PostfixedAbbreviation),
            (' ', JlreqClass::WesternWordSpace),
            ('A', JlreqClass::Western),
        ];
        for (character, expected) in cases {
            assert_eq!(classify(character), expected, "wrong class for {character}");
        }
    }

    #[test]
    fn generated_rules_cover_every_class_pair() {
        assert_eq!(PAIR_RULES.len(), JlreqClass::COUNT);
        assert!(PAIR_RULES.iter().all(|row| row.len() == JlreqClass::COUNT));
    }

    #[test]
    fn generated_rules_encode_kinsoku_and_atomic_runs() {
        assert!(!pair_rule(JlreqClass::OpeningBracket, JlreqClass::Ideographic).break_allowed);
        assert!(!pair_rule(JlreqClass::Ideographic, JlreqClass::ClosingBracket).break_allowed);
        assert!(!pair_rule(JlreqClass::GroupedNumeral, JlreqClass::GroupedNumeral).break_allowed);
        assert!(pair_rule(JlreqClass::Ideographic, JlreqClass::Ideographic).break_allowed);
    }

    #[test]
    fn generated_rules_encode_script_and_tcy_spacing() {
        let script = pair_rule(JlreqClass::Ideographic, JlreqClass::Western);
        assert_eq!(script.preferred_em, 0.25);
        assert_eq!(script.minimum_em, 0.125);
        assert_eq!(script.maximum_em, 0.5);

        let tcy = pair_rule(JlreqClass::Comma, JlreqClass::TateChuYoko);
        assert_eq!(tcy.preferred_em, 0.5);
        assert_eq!(
            pair_rule(JlreqClass::TateChuYoko, JlreqClass::Comma).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JlreqClass::OpeningBracket, JlreqClass::TateChuYoko).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JlreqClass::TateChuYoko, JlreqClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JlreqClass::Ideographic, JlreqClass::TateChuYoko).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JlreqClass::TateChuYoko, JlreqClass::Ideographic).preferred_em,
            0.0
        );
        let adjacent_tcy = pair_rule(JlreqClass::TateChuYoko, JlreqClass::TateChuYoko);
        assert_eq!(adjacent_tcy.preferred_em, 0.0);
        assert_eq!(adjacent_tcy.maximum_em, 0.25);
        assert_eq!(adjacent_tcy.expand_priority, 3);
    }

    #[test]
    fn generated_rules_encode_punctuation_sequences() {
        assert_eq!(
            pair_rule(JlreqClass::Ideographic, JlreqClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JlreqClass::ClosingBracket, JlreqClass::Ideographic).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JlreqClass::FullStop, JlreqClass::ClosingBracket).preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(JlreqClass::ClosingBracket, JlreqClass::OpeningBracket).preferred_em,
            0.5
        );
        assert_eq!(
            pair_rule(JlreqClass::ClosingBracket, JlreqClass::MiddleDot).preferred_em,
            0.25
        );
        assert_eq!(
            pair_rule(JlreqClass::MiddleDot, JlreqClass::OpeningBracket).preferred_em,
            0.25
        );
        assert_eq!(
            pair_rule(JlreqClass::DividingPunctuation, JlreqClass::Ideographic).preferred_em,
            1.0
        );
        assert_eq!(
            pair_rule(
                JlreqClass::DividingPunctuation,
                JlreqClass::DividingPunctuation
            )
            .preferred_em,
            0.0
        );
        assert_eq!(
            pair_rule(
                JlreqClass::DividingPunctuation,
                JlreqClass::IdeographicSpace
            )
            .preferred_em,
            0.0
        );
        let solid_japanese = pair_rule(JlreqClass::Ideographic, JlreqClass::Hiragana);
        assert_eq!(solid_japanese.preferred_em, 0.0);
        assert_eq!(solid_japanese.maximum_em, 0.25);
        assert_eq!(solid_japanese.expand_priority, 3);
        let ruby_adjacency = pair_rule(JlreqClass::SimpleRuby, JlreqClass::JukugoRuby);
        assert_eq!(ruby_adjacency.preferred_em, 0.0);
        assert_eq!(ruby_adjacency.maximum_em, 0.0);
        assert_eq!(ruby_adjacency.expand_priority, 0);
    }

    #[test]
    fn classify_follows_jlreq_appendix_a() {
        let cases = [
            ('—', JlreqClass::Inseparable),
            ('〝', JlreqClass::OpeningBracket),
            ('〟', JlreqClass::ClosingBracket),
            ('〞', JlreqClass::ClosingBracket),
            ('«', JlreqClass::OpeningBracket),
            ('»', JlreqClass::ClosingBracket),
            ('⦅', JlreqClass::OpeningBracket),
            ('⦆', JlreqClass::ClosingBracket),
            ('｟', JlreqClass::OpeningBracket),
            ('｠', JlreqClass::ClosingBracket),
            ('Ａ', JlreqClass::Ideographic),
            ('ｚ', JlreqClass::Ideographic),
            ('１', JlreqClass::Ideographic),
            ('〇', JlreqClass::Ideographic),
            ('〒', JlreqClass::Ideographic),
            ('※', JlreqClass::Ideographic),
            ('①', JlreqClass::Ideographic),
            ('㈱', JlreqClass::Ideographic),
            ('○', JlreqClass::Ideographic),
            ('★', JlreqClass::Ideographic),
            ('→', JlreqClass::Ideographic),
            ('Ⅳ', JlreqClass::Ideographic),
            ('㏍', JlreqClass::Ideographic),
            ('≒', JlreqClass::MathSymbol),
            ('≡', JlreqClass::MathSymbol),
            ('≪', JlreqClass::MathSymbol),
            ('≈', JlreqClass::MathSymbol),
            ('∝', JlreqClass::MathSymbol),
            ('↔', JlreqClass::MathSymbol),
            ('€', JlreqClass::PrefixedAbbreviation),
            ('㎡', JlreqClass::PostfixedAbbreviation),
            ('㏄', JlreqClass::PostfixedAbbreviation),
            ('㌔', JlreqClass::PostfixedAbbreviation),
            ('ℓ', JlreqClass::PostfixedAbbreviation),
            ('｡', JlreqClass::FullStop),
            ('､', JlreqClass::Comma),
            ('｢', JlreqClass::OpeningBracket),
            ('｣', JlreqClass::ClosingBracket),
            ('ｧ', JlreqClass::SmallKana),
            ('ｯ', JlreqClass::SmallKana),
            ('ｰ', JlreqClass::ProlongedSoundMark),
            ('\u{30000}', JlreqClass::Ideographic),
            ('\u{31350}', JlreqClass::Ideographic),
        ];
        for (character, expected) in cases {
            assert_eq!(classify(character), expected, "wrong class for {character}");
        }
    }

    #[test]
    fn fast_classes_match_the_tables() {
        let fast = ('\u{4E00}'..='\u{9FFF}')
            .chain('\u{3400}'..='\u{4DBF}')
            .chain('a'..='z')
            .chain('A'..='Z');
        for c in fast {
            assert_eq!(classify(c), classify_by_tables(c), "{c:?}");
        }
    }

    #[test]
    fn ascii_and_latin_symbols_stay_western() {
        for character in ['(', ')', '[', ']', 'A', '©', '†', 'α'] {
            assert_eq!(classify(character), JlreqClass::Western, "{character}");
        }
    }

    #[test]
    fn only_identical_inseparable_pairs_are_unbreakable() {
        assert!(!JlreqClass::Inseparable.forbids_line_start());
        assert!(break_allowed_between('あ', '…'));
        assert!(!break_allowed_between('…', '…'));
        assert!(!break_allowed_between('—', '—'));
        assert!(break_allowed_between('—', '…'));
    }

    #[test]
    fn abbreviations_bind_only_to_numerals() {
        assert!(!JlreqClass::PostfixedAbbreviation.forbids_line_start());
        assert!(!JlreqClass::PrefixedAbbreviation.forbids_line_end());
        assert!(!break_allowed_between('0', '㎏'));
        assert!(!break_allowed_between('０', '％'));
        assert!(!break_allowed_between('$', '5'));
        assert!(!break_allowed_between('￥', '１'));
        assert!(break_allowed_between('#', 'は'));
        assert!(break_allowed_between('漢', '％'));
    }

    #[test]
    fn breaks_follow_kinsoku_and_western_runs() {
        assert!(!break_allowed_between('あ', 'っ'));
        assert!(!break_allowed_between('「', 'あ'));
        assert!(!break_allowed_between('P', 'e'));
        assert!(!break_allowed_between('2', '0'));
        assert!(break_allowed_between('あ', 'い'));
    }

    #[test]
    fn digit_runs_stay_together() {
        assert!(!break_allowed_between('２', '０'));
        assert!(!break_allowed_between('2', '０'));
        assert!(break_allowed_between('６', '年'));
        assert!(break_allowed_between('年', '２'));
    }
}
