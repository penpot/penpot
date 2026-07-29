# Common Text Subtleties

## DraftJS compatibility

- `app.common.text` is legacy DraftJS conversion support. New text work should prefer the newer text type namespaces unless specifically touching DraftJS conversion.
- DraftJS style values are encoded as Transit strings under `PENPOT$$$<key>$$$<encoded>` style names. `PENPOT_SELECTION` is a special marker.
- Text conversion uses Unicode code points on both CLJ and CLJS paths, not UTF-16 code units. This matters for offsets around emoji and astral characters.
- Draft conversion fixes gradient type strings back to keywords.

## Modern text content

- Modern text content schema is narrow: root -> paragraph-set -> paragraph -> text nodes.
- `position-data` is derived layout/geometry/font fragment data and should be treated as generated state, not source-of-truth file data.
- Token propagation and some non-current-page text updates drop `:position-data` so it can be regenerated in the right runtime context.