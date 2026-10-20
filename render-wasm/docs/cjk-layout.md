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

## Pinyin readings

The Chinese layout panel offers typed Pinyin readings when the selected
paragraph has an explicit Chinese profile. In the WASM text editor, select
one character or a group within one text style, then enter its reading.
To edit an existing reading, select its whole base. Selections across styles
or paragraphs do not offer a reading field, which prevents repeated readings.
For character-by-character readings, annotate each character separately.

Readings use the saved `ruby` attributes and the shared annotation renderer.
They support size, alignment, overhang, side, hiding and annotation clearance.
Without a text selection, presentation changes affect existing readings.
Horizontal readings form one Latin group above or below the base; vertical
readings form one sideways Latin group beside it. Pronunciation is entered
by the author; the editor does not generate or distribute syllables.

Composed and combining tone marks retain their source text and shaping.
Glyphs sharing a cluster retain the same complete source range. Tests use
`src/fonts/pinyin-test.ttf`, an 8 KB subset of the repository's Work Sans
Regular font, with letters, umlauts and combining tone marks. The subset
retains its copyright and SIL Open Font License metadata; its family name
is Pinyin Test. FontTools generated it from the Pinyin test strings.

## Emphasis and underline positions

Chinese profiles expose emphasis shape, emphasis position, underline position
and annotation clearance independently of Pinyin. Position controls do not
turn on underlining; use the existing underline control for that.

Optional span `text-emphasis-position` stores `auto`, `over-right`,
`over-left`, `under-right` or `under-left`. The first part selects the
horizontal side; the second selects the vertical side. Auto uses below/right
for Chinese and preserves above/right for Japanese, Korean and plain text.

Optional `text-underline-position` stores `auto`, `under`, `left`, `right`,
`under-left` or `under-right`. Under places horizontal underlines below the
base rect; left/right select the vertical side. Chinese auto uses below/left.
Other auto values preserve the earlier baseline offset and vertical left
side. Explicit values can select other styles without changing the language.
These are supported POC choices, not a claim of complete decoration support.

Automatic clearance reserves each occupied side. Stacked annotations retain
the largest reading and mark sizes across mixed-font spans, preserving the
prior Japanese column widths. Underlines remain next to
the base; readings move outside an underline on their side, and emphasis
moves outside a reading on its side. The underline band reserves 0.18 em,
a POC bound around the existing 1/9-em gap and stroke width. Disabling
clearance retains fixed line-height behavior and can allow overlap/clipping.

Decoration edits that intersect a reading or note apply to its whole base,
so changing a side cannot duplicate its reading on split spans. Other text
styles remain unchanged. Selection snapshots, Plugin API properties,
CSS handoff and saved content retain the independent positions. CSS auto
positions inherit the Chinese paragraph defaults; explicit positions emit
the corresponding CSS keyword pairs. The binary writer uses former span
header bytes 14/15; header size and prior offsets stay unchanged. Unknown
bytes decode to auto. Both WASM consumers share that writer.

Defaults and positions were checked on 2026-10-20 against
[CSS Text Decoration, emphasis](https://www.w3.org/TR/css-text-decor-3/#text-emphasis-position-property)
and [underline positions](https://www.w3.org/TR/css-text-decor-3/#text-underline-position-property).
Japanese/Korean vertical auto underlines retain this branch's existing left
side rather than adopting the spec's suggested right-side style.

## Evidence and release checks

Renderer tests cover regional geometry, horizontal/vertical wrapping,
style boundaries, decomposed Jamo, punctuation, justification, stable offsets
and distinct wrap protocol bytes. Editor splits preserve the complete wrap
mode. Both WASM consumers use the same writer and byte values: 0 syllable,
1 experiment, 2 keep-words; unknown values fall back to syllable wrapping.

Real Pinyin, Zhuyin and Korean IME checks and native-reader review remain
release checks. Font glyph coverage and localized forms still depend on the
chosen fonts. Dedicated Zhuyin layout remains a Stage 4 follow-up.

References checked on 2026-10-19:

- [CLREQ draft, 2026-10-07](https://www.w3.org/TR/2026/DNOTE-clreq-20261007/),
  sections 6.1 and 6.3.2: punctuation boundaries, compression and hanging.
- [KLREQ draft, 2026-03-21](https://www.w3.org/TR/2026/DNOTE-klreq-20260321/),
  sections 7.1 and 7.2: syllable/word wrapping and justification restrictions.
- [CSS Text 3](https://www.w3.org/TR/css-text-3/#word-break-property):
  letter-breaking policy is distinct from punctuation and overflow handling.

### Mandarin Zhuyin annotations

An optional span flag, `ruby-zhuyin`, selects dedicated right-side Zhuyin.
Absent or false retains normal ruby. Each span maps one Han character
(optionally with one variation selector) to one typed Mandarin syllable in
`ruby`. Enter 1–3 U+3105–U+3129 symbols and at most one tone: U+02C9, U+02CA,
U+02C7, U+02CB or U+02D9. Neutral tone may precede or follow the symbols;
other tones must follow them. The editor validates this syntax; it does not
check pronunciation, infer mappings or generate readings. Unsupported API
content keeps its text and falls back to ordinary ruby.

Symbols remain upright to the base's right in horizontal and vertical text.
Their size is 0.3 em. A 0.6 em strip holds symbols and tones, including an
empty tone lane for unmarked first tone. Non-neutral tones sit outside the
upper right of the last symbol; neutral tone sits above the symbol stack.
The stack centres vertically on the base. These rules follow CLREQ
sections 5.5.3.2–5.5.3.3, retrieved 2026-10-20:
<https://www.w3.org/TR/clreq/>. The strip is wider than the reference's
half-em minimum to contain a full-width tone fallback glyph.

Horizontal layout reserves the strip in the inline advance. Vertical Auto
clearance reserves it in the right gutter, outside a same-side underline
and inside same-side emphasis. Fixed clearance may allow overlaps, as with
ordinary ruby. Zhuyin ignores ruby size, alignment, overhang and side;
hiding a reading removes its room without clearing saved text or mode.

The mode uses bit 1 of span header byte 13; bit 0 keeps over/under for normal
ruby. Header size stays unchanged. Unknown flag bits fall back to ordinary
over ruby. Position-data direction 5 carries a single upright annotation
glyph with offsets into the original ruby string and its drawing baseline.
Both layout paths use it for SVG; canvas, PNG, PDF and vertical outlines use
the same shaped glyph positions. HTML handoff uses positioned symbol boxes;
font metrics can make it differ from the renderer's ink-centred placement.

This slice supports Mandarin, one base per span. Group-to-syllable mapping,
dialectal symbols/tones, automatic pronunciation, native IME acceptance and
native-reader approval remain outside this slice. Changing a base into a
group preserves the reading and uses normal ruby until a single base is
restored. Existing Pinyin and Japanese readings keep their saved behavior.
