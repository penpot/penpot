# Chinese and Korean core layout

Paragraph `text-language` selects a layout policy. Writing mode remains a
whole-shape value. UI availability never changes either attribute.

## Named Chinese presets

| Tags | Preset |
| --- | --- |
| `zh`, `zh-Hans`, `zh-Hant`, unknown regions | Original basic punctuation policy |
| `zh-Hans-CN`, `zh-Hant-CN`, `zh-CN`, `zh-SG` | Mainland: adjacent punctuation compression; quarter-em Han/Latin gaps |
| `zh-Hant-TW`, `zh-TW` | Taiwan: solid punctuation; quarter-em Han/Latin gaps |
| `zh-Hant-HK`, `zh-HK`, `zh-MO` | Hong Kong: centered punctuation spacing; quarter-em Han/Latin gaps |

Script does not imply region. Changing a profile does not convert characters.
These presets select a limited set of styles; they do not claim that every
publication in a region uses the same settings.

Compression applies to eligible adjacent full-width punctuation. Vertical
colon, semicolon, question and exclamation marks keep their full frame.
Proportional horizontal punctuation is not compressed again. Vertical cells
retain at least a half-em frame. The Taiwan and Hong Kong presets allow a
single comma or full stop to hang by at most half an em vertically; adjacent
punctuation and horizontal text do not hang.

Chinese justification expands eligible character gaps by at most half an em.
The final line/column remains unexpanded. These bounds are explicit POC
choices, not numeric requirements imposed by a regional standard.

## Korean modes

- Missing `korean-wrap`, or `syllable`: syllable wrapping with punctuation rules.
- `keep-words`: keep adjoining letters and digits together across scripts and
  style runs. Punctuation keeps its own opportunities and restrictions.
- `spaces-only`: preserve the earlier experiment. It remains readable in the
  UI for saved text but is not offered for new selections by default.

Fitting words move whole. Overlong words may use safe grapheme boundaries.
Nonbreaking spaces/joiners, numeric prefixes/suffixes, paired dashes/ellipses,
annotations and punctuation restrictions remain separate from letter ties.
Korean justification first expands spaces, at most half an em per gap.
Syllable mode may then expand eligible character gaps by at most one eighth
of an em. Word mode never expands inside a word. Original spaces remain in
source text; no Japanese word-space substitution applies.

## Horizontal emergency wrapping and offsets

SkParagraph can ignore word joins during emergency wrapping. The horizontal
plan measures candidate units and replaces joins with zero-width break
opportunities only inside overlong units, at safe grapheme boundaries.
Both marker characters occupy three UTF-8 bytes and one UTF-16 code unit.
Their offset maps therefore stay identical for drawing, hit testing, carets,
selection, text paths and export. Width participates in the plan cache key;
resizing a box restores whole-word joins when the word fits again.

A horizontal indivisible unit wider than the entire box remains subject to
SkParagraph's last-resort fallback. Very narrow boxes containing punctuation
or annotation units need further work; this implementation does not claim
complete CSS `keep-all` conformance.

## Evidence and release checks

Renderer tests cover regional geometry, horizontal/vertical wrapping,
style boundaries, decomposed Jamo, punctuation, justification, stable offsets
and distinct wrap protocol bytes. Editor splits preserve the complete wrap
mode. Both WASM consumers use the same writer and byte values: 0 syllable,
1 experiment, 2 keep-words; unknown values fall back to syllable wrapping.

Real Pinyin, Zhuyin and Korean IME checks and native-reader review remain
release checks. Font glyph coverage and localized forms still depend on the
chosen fonts. Pinyin/Zhuyin annotation tools belong to Stage 4.

References checked on 2026-10-19:

- [CLREQ draft, 2026-10-07](https://www.w3.org/TR/2026/DNOTE-clreq-20261007/),
  sections 6.1 and 6.3.2: punctuation boundaries, compression and hanging.
- [KLREQ draft, 2026-03-21](https://www.w3.org/TR/2026/DNOTE-klreq-20260321/),
  sections 7.1 and 7.2: syllable/word wrapping and justification restrictions.
- [CSS Text 3](https://www.w3.org/TR/css-text-3/#word-break-property):
  letter-breaking policy is distinct from punctuation and overflow handling.
