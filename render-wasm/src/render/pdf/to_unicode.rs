//! Rewrites the `/ToUnicode` maps of a Skia PDF's Type0 fonts in an appended
//! incremental update, leaving the bytes Skia wrote untouched.

use std::collections::{BTreeMap, HashMap};

use super::glyph_text::GlyphTexts;

/// Glyph code to UTF-16 text.
type CMap = BTreeMap<u16, Vec<u16>>;

/// Returns `pdf` with fixed `/ToUnicode` maps, or unchanged when nothing needs
/// fixing or the file is not laid out as expected.
pub fn fix_to_unicode(pdf: Vec<u8>, glyph_texts: &GlyphTexts) -> Vec<u8> {
    if glyph_texts.is_empty() {
        return pdf;
    }
    match updated_cmaps(&pdf, glyph_texts) {
        Some(Update {
            cmaps,
            xref_offset,
            trailer,
        }) if !cmaps.is_empty() => append_update(pdf, &cmaps, xref_offset, &trailer),
        _ => pdf,
    }
}

struct Update {
    /// ToUnicode object number to its new CMap.
    cmaps: BTreeMap<u32, CMap>,
    xref_offset: usize,
    trailer: Vec<u8>,
}

fn updated_cmaps(pdf: &[u8], glyph_texts: &GlyphTexts) -> Option<Update> {
    let xref = parse_xref(pdf)?;
    let mut cmaps = BTreeMap::new();

    for &offset in xref.offsets.values() {
        let Some(font) = read_object(pdf, offset) else {
            continue;
        };
        if dict_name(font.dict, b"/Type") != Some(b"Font".as_slice())
            || dict_name(font.dict, b"/Subtype") != Some(b"Type0".as_slice())
        {
            continue;
        }
        let (Some(base_font), Some(to_unicode)) = (
            dict_name(font.dict, b"/BaseFont"),
            dict_ref(font.dict, b"/ToUnicode"),
        ) else {
            continue;
        };
        let font_name = strip_subset_tag(&decode_name(base_font));
        let Some(stream) = xref
            .offsets
            .get(&to_unicode)
            .and_then(|offset| read_object(pdf, *offset))
            .and_then(|object| stream_data(&object))
        else {
            continue;
        };

        let mut cmap = parse_cmap(&stream);
        let mut changed = false;
        for (code, value) in cmap.iter_mut() {
            let current = String::from_utf16_lossy(value);
            if let Some(text) = glyph_texts.replacement(&font_name, *code, &current) {
                *value = text.encode_utf16().collect();
                changed = true;
            }
        }
        if changed {
            cmaps.insert(to_unicode, cmap);
        }
    }

    Some(Update {
        cmaps,
        xref_offset: xref.offset,
        trailer: xref.trailer,
    })
}

fn append_update(
    mut pdf: Vec<u8>,
    cmaps: &BTreeMap<u32, CMap>,
    prev_xref: usize,
    trailer: &[u8],
) -> Vec<u8> {
    if pdf.last() != Some(&b'\n') {
        pdf.push(b'\n');
    }
    let mut offsets = Vec::with_capacity(cmaps.len());
    for (&number, cmap) in cmaps {
        let data = write_cmap(cmap);
        offsets.push((number, pdf.len()));
        pdf.extend_from_slice(
            format!("{number} 0 obj\n<</Length {}>> stream\n", data.len()).as_bytes(),
        );
        pdf.extend_from_slice(&data);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
    }

    let xref_offset = pdf.len();
    pdf.extend_from_slice(b"xref\n");
    for (number, offset) in offsets {
        pdf.extend_from_slice(format!("{number} 1\n{offset:010} 00000 n \n").as_bytes());
    }
    // Same entries as the previous trailer (`/Size`, `/Root`, `/Info`, `/ID`),
    // plus `/Prev`.
    let body = &trailer[..trailer.len() - 2];
    pdf.extend_from_slice(b"trailer\n");
    pdf.extend_from_slice(body);
    pdf.extend_from_slice(
        format!("\n/Prev {prev_xref}>>\nstartxref\n{xref_offset}\n%%EOF\n").as_bytes(),
    );
    pdf
}

// ---------------------------------------------------------------------------
// CMap

fn parse_cmap(data: &[u8]) -> CMap {
    let mut cmap = CMap::new();
    let mut tokens = CMapTokens { data, pos: 0 };
    while let Some(token) = tokens.next() {
        match token {
            Token::Keyword(b"beginbfchar") => {
                while let Some(Token::Hex(code)) = tokens.next() {
                    let Some(Token::Hex(value)) = tokens.next() else {
                        break;
                    };
                    if let Some(code) = to_code(&code) {
                        cmap.insert(code, to_utf16(&value));
                    }
                }
            }
            Token::Keyword(b"beginbfrange") => {
                while let Some(Token::Hex(start)) = tokens.next() {
                    let (Some(Token::Hex(end)), Some(first)) = (tokens.next(), tokens.next())
                    else {
                        break;
                    };
                    let (Some(start), Some(end)) = (to_code(&start), to_code(&end)) else {
                        continue;
                    };
                    match first {
                        Token::Hex(value) => {
                            let value = to_utf16(&value);
                            for (step, code) in (start..=end).enumerate() {
                                let mut value = value.clone();
                                if let Some(last) = value.last_mut() {
                                    *last = last.wrapping_add(step as u16);
                                }
                                cmap.insert(code, value);
                            }
                        }
                        Token::ArrayStart => {
                            let mut code = start;
                            while let Some(Token::Hex(value)) = tokens.next() {
                                cmap.insert(code, to_utf16(&value));
                                code = code.wrapping_add(1);
                            }
                        }
                        _ => break,
                    }
                }
            }
            _ => {}
        }
    }
    cmap
}

fn write_cmap(cmap: &CMap) -> Vec<u8> {
    let mut out = String::from(
        "/CIDInit /ProcSet findresource begin\n\
         12 dict begin\n\
         begincmap\n\
         /CIDSystemInfo\n\
         <<  /Registry (Adobe)\n\
         /Ordering (UCS)\n\
         /Supplement 0\n\
         >> def\n\
         /CMapName /Adobe-Identity-UCS def\n\
         /CMapType 2 def\n\
         1 begincodespacerange\n\
         <0000> <FFFF>\n\
         endcodespacerange\n",
    );
    let entries: Vec<_> = cmap.iter().collect();
    // At most 100 entries per section (PDF 32000-1, 9.10.3).
    for chunk in entries.chunks(100) {
        out.push_str(&format!("{} beginbfchar\n", chunk.len()));
        for (code, value) in chunk {
            out.push_str(&format!("<{code:04X}> <"));
            for unit in value.iter() {
                out.push_str(&format!("{unit:04X}"));
            }
            out.push_str(">\n");
        }
        out.push_str("endbfchar\n");
    }
    out.push_str(
        "endcmap\n\
         CMapName currentdict /CMap defineresource pop\n\
         end\n\
         end",
    );
    out.into_bytes()
}

enum Token<'a> {
    Hex(Vec<u8>),
    ArrayStart,
    ArrayEnd,
    Keyword(&'a [u8]),
    Other,
}

struct CMapTokens<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Iterator for CMapTokens<'a> {
    type Item = Token<'a>;

    fn next(&mut self) -> Option<Token<'a>> {
        self.pos = skip_ws(self.data, self.pos);
        let byte = *self.data.get(self.pos)?;
        let start = self.pos;
        self.pos += 1;
        match byte {
            b'<' if self.data.get(self.pos) == Some(&b'<') => {
                self.pos += 1;
                Some(Token::Other)
            }
            b'<' => {
                let end = find(self.data, b">", self.pos)?;
                let hex = parse_hex(&self.data[self.pos..end]);
                self.pos = end + 1;
                Some(Token::Hex(hex))
            }
            b'[' => Some(Token::ArrayStart),
            b']' => Some(Token::ArrayEnd),
            _ => {
                while self
                    .data
                    .get(self.pos)
                    .is_some_and(|b| !is_ws(*b) && !is_delimiter(*b))
                {
                    self.pos += 1;
                }
                Some(Token::Keyword(&self.data[start..self.pos]))
            }
        }
    }
}

fn parse_hex(hex: &[u8]) -> Vec<u8> {
    let digits: Vec<u8> = hex
        .iter()
        .filter_map(|b| (*b as char).to_digit(16).map(|d| d as u8))
        .collect();
    digits
        .chunks(2)
        .map(|pair| (pair[0] << 4) | pair.get(1).copied().unwrap_or(0))
        .collect()
}

fn to_code(bytes: &[u8]) -> Option<u16> {
    match bytes {
        [byte] => Some(*byte as u16),
        [high, low] => Some(u16::from_be_bytes([*high, *low])),
        _ => None,
    }
}

fn to_utf16(bytes: &[u8]) -> Vec<u16> {
    bytes
        .chunks(2)
        .map(|pair| u16::from_be_bytes([pair[0], pair.get(1).copied().unwrap_or(0)]))
        .collect()
}

// ---------------------------------------------------------------------------
// PDF objects

struct Xref {
    /// Byte offset of the `xref` keyword.
    offset: usize,
    /// Object number to byte offset, for objects in use.
    offsets: HashMap<u32, usize>,
    /// The trailer dictionary, `<<` to `>>`.
    trailer: Vec<u8>,
}

/// Reads the last xref table and its trailer. Skia writes a single classic
/// table, without xref streams.
fn parse_xref(pdf: &[u8]) -> Option<Xref> {
    let startxref = rfind(pdf, b"startxref")?;
    let (offset, _) = parse_uint(pdf, skip_ws(pdf, startxref + b"startxref".len()))?;
    parse_xref_at(pdf, offset as usize)
}

fn parse_xref_at(pdf: &[u8], offset: usize) -> Option<Xref> {
    if !pdf.get(offset..)?.starts_with(b"xref") {
        return None;
    }
    let mut offsets = HashMap::new();
    let mut pos = offset + b"xref".len();
    loop {
        pos = skip_ws(pdf, pos);
        if pdf.get(pos..)?.starts_with(b"trailer") {
            break;
        }
        let (first, next) = parse_uint(pdf, pos)?;
        let (count, next) = parse_uint(pdf, skip_ws(pdf, next))?;
        pos = next;
        for number in first..first + count {
            let (entry_offset, next) = parse_uint(pdf, skip_ws(pdf, pos))?;
            let (_generation, next) = parse_uint(pdf, skip_ws(pdf, next))?;
            pos = skip_ws(pdf, next);
            if *pdf.get(pos)? == b'n' {
                offsets.insert(number as u32, entry_offset as usize);
            }
            pos += 1;
        }
    }
    let start = skip_ws(pdf, pos + b"trailer".len());
    let end = skip_dict(pdf, start)?;
    Some(Xref {
        offset,
        offsets,
        trailer: pdf[start..end].to_vec(),
    })
}

struct Object<'a> {
    dict: &'a [u8],
    stream: Option<&'a [u8]>,
}

/// Reads the dictionary (and stream, if any) of the object at `offset`.
fn read_object(pdf: &[u8], offset: usize) -> Option<Object<'_>> {
    let (_, pos) = parse_uint(pdf, offset)?;
    let (_, pos) = parse_uint(pdf, skip_ws(pdf, pos))?;
    let pos = skip_ws(pdf, pos);
    if !pdf.get(pos..)?.starts_with(b"obj") {
        return None;
    }
    let start = skip_ws(pdf, pos + b"obj".len());
    let end = skip_dict(pdf, start)?;
    let dict = &pdf[start..end];

    let mut pos = skip_ws(pdf, end);
    if !pdf.get(pos..)?.starts_with(b"stream") {
        return Some(Object { dict, stream: None });
    }
    pos += b"stream".len();
    if pdf.get(pos) == Some(&b'\r') {
        pos += 1;
    }
    if pdf.get(pos) == Some(&b'\n') {
        pos += 1;
    }
    let length = dict_uint(dict, b"/Length")? as usize;
    Some(Object {
        dict,
        stream: Some(pdf.get(pos..pos + length)?),
    })
}

fn stream_data(object: &Object) -> Option<Vec<u8>> {
    let stream = object.stream?;
    match dict_name(object.dict, b"/Filter") {
        None => Some(stream.to_vec()),
        Some(b"FlateDecode") => miniz_oxide::inflate::decompress_to_vec_zlib(stream).ok(),
        Some(_) => None,
    }
}

/// Position right after the `>>` closing the dictionary at `pos`.
fn skip_dict(pdf: &[u8], pos: usize) -> Option<usize> {
    if !pdf.get(pos..)?.starts_with(b"<<") {
        return None;
    }
    let mut depth = 0;
    let mut pos = pos;
    while pos < pdf.len() {
        match pdf[pos] {
            b'<' if pdf.get(pos + 1) == Some(&b'<') => {
                depth += 1;
                pos += 2;
            }
            b'>' if pdf.get(pos + 1) == Some(&b'>') => {
                depth -= 1;
                pos += 2;
                if depth == 0 {
                    return Some(pos);
                }
            }
            b'<' => pos = find(pdf, b">", pos)? + 1,
            b'(' => pos = skip_string(pdf, pos)?,
            _ => pos += 1,
        }
    }
    None
}

/// Position right after the literal string starting at `pos`.
fn skip_string(pdf: &[u8], pos: usize) -> Option<usize> {
    let mut depth = 0;
    let mut pos = pos;
    while pos < pdf.len() {
        match pdf[pos] {
            b'\\' => pos += 1,
            b'(' => depth += 1,
            b')' => {
                depth -= 1;
                if depth == 0 {
                    return Some(pos + 1);
                }
            }
            _ => {}
        }
        pos += 1;
    }
    None
}

/// Position of the value for `key`, matching whole keys only.
fn dict_value<'a>(dict: &'a [u8], key: &[u8]) -> Option<&'a [u8]> {
    let mut from = 0;
    while let Some(at) = find(dict, key, from) {
        let end = at + key.len();
        if dict.get(end).is_some_and(|b| is_ws(*b) || is_delimiter(*b)) {
            return Some(&dict[skip_ws(dict, end)..]);
        }
        from = end;
    }
    None
}

fn dict_name<'a>(dict: &'a [u8], key: &[u8]) -> Option<&'a [u8]> {
    let value = dict_value(dict, key)?.strip_prefix(b"/")?;
    let end = value
        .iter()
        .position(|b| is_ws(*b) || is_delimiter(*b))
        .unwrap_or(value.len());
    Some(&value[..end])
}

fn dict_uint(dict: &[u8], key: &[u8]) -> Option<u64> {
    parse_uint(dict_value(dict, key)?, 0).map(|(value, _)| value)
}

/// Object number of an indirect reference (`N 0 R`).
fn dict_ref(dict: &[u8], key: &[u8]) -> Option<u32> {
    let value = dict_value(dict, key)?;
    let (number, pos) = parse_uint(value, 0)?;
    let (_, pos) = parse_uint(value, skip_ws(value, pos))?;
    (value.get(skip_ws(value, pos)) == Some(&b'R')).then_some(number as u32)
}

/// Decodes `#xx` escapes in a PDF name.
fn decode_name(name: &[u8]) -> String {
    let mut out = Vec::with_capacity(name.len());
    let mut i = 0;
    while i < name.len() {
        if name[i] == b'#' && i + 2 < name.len() {
            if let [byte] = parse_hex(&name[i + 1..i + 3])[..] {
                out.push(byte);
                i += 3;
                continue;
            }
        }
        out.push(name[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Drops the `ABCDEF+` subset tag Skia puts before every font name.
fn strip_subset_tag(name: &str) -> String {
    let bytes = name.as_bytes();
    if bytes.len() > 7 && bytes[6] == b'+' && bytes[..6].iter().all(u8::is_ascii_uppercase) {
        name[7..].to_string()
    } else {
        name.to_string()
    }
}

fn parse_uint(data: &[u8], pos: usize) -> Option<(u64, usize)> {
    let digits = data
        .get(pos..)?
        .iter()
        .take_while(|b| b.is_ascii_digit())
        .count();
    if digits == 0 {
        return None;
    }
    let value = std::str::from_utf8(&data[pos..pos + digits])
        .ok()?
        .parse()
        .ok()?;
    Some((value, pos + digits))
}

fn skip_ws(data: &[u8], mut pos: usize) -> usize {
    while data.get(pos).is_some_and(|b| is_ws(*b)) {
        pos += 1;
    }
    pos
}

fn is_ws(byte: u8) -> bool {
    matches!(byte, b' ' | b'\n' | b'\r' | b'\t' | b'\x0c' | b'\0')
}

fn is_delimiter(byte: u8) -> bool {
    matches!(
        byte,
        b'/' | b'<' | b'>' | b'[' | b']' | b'(' | b')' | b'{' | b'}' | b'%'
    )
}

fn find(hay: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    hay.get(from..)?
        .windows(needle.len())
        .position(|window| window == needle)
        .map(|at| at + from)
}

fn rfind(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len())
        .rposition(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    use skia_safe as skia;

    use crate::render::RenderResources;
    use crate::shapes::{
        Fill, FontFamily, FontStyle, GrowType, Paragraph, SolidColor, TextAlign, TextContent,
        TextDirection, TextSpan, Type,
    };
    use crate::state::ShapesPool;
    use crate::utils::uuid_from_u32_quartet;
    use crate::uuid::Uuid;

    fn cmap(entries: &[(u16, &str)]) -> CMap {
        entries
            .iter()
            .map(|(code, text)| (*code, text.encode_utf16().collect()))
            .collect()
    }

    /// Objects visible in the final file, following `/Prev` back to the
    /// first xref table.
    fn latest_offsets(pdf: &[u8]) -> HashMap<u32, usize> {
        let mut xref = parse_xref(pdf).expect("xref");
        let mut offsets = xref.offsets.clone();
        while let Some(prev) = dict_uint(&xref.trailer, b"/Prev") {
            xref = parse_xref_at(pdf, prev as usize).expect("previous xref");
            for (number, offset) in &xref.offsets {
                offsets.entry(*number).or_insert(*offset);
            }
        }
        offsets
    }

    fn object<'a>(pdf: &'a [u8], offsets: &HashMap<u32, usize>, number: u32) -> Object<'a> {
        read_object(pdf, offsets[&number]).expect("object")
    }

    /// The Type0 font's ToUnicode map, as seen by a reader.
    fn to_unicode_of_only_font(pdf: &[u8]) -> CMap {
        let offsets = latest_offsets(pdf);
        let fonts: Vec<_> = offsets
            .values()
            .filter_map(|offset| read_object(pdf, *offset))
            .filter(|o| dict_name(o.dict, b"/Subtype") == Some(b"Type0".as_slice()))
            .collect();
        assert_eq!(fonts.len(), 1, "expected a single Type0 font");
        let number = dict_ref(fonts[0].dict, b"/ToUnicode").expect("ToUnicode ref");
        parse_cmap(&stream_data(&object(pdf, &offsets, number)).expect("cmap data"))
    }

    /// Builds a classic PDF from object bodies numbered from 1.
    fn build_pdf(objects: &[Vec<u8>]) -> Vec<u8> {
        let mut pdf = b"%PDF-1.4\n".to_vec();
        let mut offsets = Vec::new();
        for (i, body) in objects.iter().enumerate() {
            offsets.push(pdf.len());
            pdf.extend_from_slice(format!("{} 0 obj\n", i + 1).as_bytes());
            pdf.extend_from_slice(body);
            pdf.extend_from_slice(b"\nendobj\n");
        }
        let xref = pdf.len();
        pdf.extend_from_slice(
            format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
        );
        for offset in offsets {
            pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        pdf.extend_from_slice(
            format!(
                "trailer\n<</Size {}\n/Root 1 0 R>>\nstartxref\n{xref}\n%%EOF",
                objects.len() + 1
            )
            .as_bytes(),
        );
        pdf
    }

    fn stream_object(dict: &str, data: &[u8]) -> Vec<u8> {
        let mut body = format!("<<{dict}/Length {}>> stream\n", data.len()).into_bytes();
        body.extend_from_slice(data);
        body.extend_from_slice(b"\nendstream");
        body
    }

    #[test]
    fn parses_bfchar_and_both_bfrange_forms() {
        let data = b"2 beginbfchar\n<0003> <0020>\n<0332> <00660066>\nendbfchar\n\
            2 beginbfrange\n<0010> <0012> <0041>\n<0020> <0021> [<0061> <D83DDE00>]\nendbfrange\n";

        assert_eq!(
            parse_cmap(data),
            cmap(&[
                (0x03, " "),
                (0x10, "A"),
                (0x11, "B"),
                (0x12, "C"),
                (0x20, "a"),
                (0x21, "\u{1F600}"),
                (0x0332, "ff"),
            ])
        );
    }

    #[test]
    fn written_cmap_parses_back() {
        let entries: Vec<(u16, &str)> = (0..250).map(|code| (code, "x")).collect();
        let mut original = cmap(&entries);
        original.insert(0x0332, "ffi".encode_utf16().collect());

        let written = write_cmap(&original);

        assert_eq!(parse_cmap(&written), original);
        assert!(String::from_utf8(written)
            .unwrap()
            .contains("100 beginbfchar"));
    }

    #[test]
    fn appends_fixed_cmap_as_incremental_update() {
        let skia_cmap = write_cmap(&cmap(&[(0x03, " "), (0x0332, "\0"), (0x0335, "\u{FB02}")]));
        let compressed = miniz_oxide::deflate::compress_to_vec_zlib(&skia_cmap, 6);
        let pdf = build_pdf(&[
            b"<</Type /Catalog>>".to_vec(),
            b"<</Type /Font\n/Subtype /Type0\n/BaseFont /AAAAAA+My#20Font\n/ToUnicode 3 0 R>>"
                .to_vec(),
            stream_object("/Filter /FlateDecode", &compressed),
        ]);
        let texts = GlyphTexts::default()
            .with_glyph("My Font", 0x0332, "ffi")
            .with_glyph("My Font", 0x0335, "fl")
            .with_glyph("My Font", 0x03, " ");

        let fixed = fix_to_unicode(pdf.clone(), &texts);

        assert!(
            fixed.starts_with(&pdf),
            "original bytes must stay untouched"
        );
        assert_eq!(
            to_unicode_of_only_font(&fixed),
            cmap(&[(0x03, " "), (0x0332, "ffi"), (0x0335, "fl")])
        );
        let trailer = parse_xref(&fixed).unwrap().trailer;
        assert_eq!(dict_ref(&trailer, b"/Root"), Some(1));
        assert_eq!(dict_uint(&trailer, b"/Size"), Some(4));
    }

    #[test]
    fn leaves_pdf_alone_when_nothing_changes() {
        let pdf = build_pdf(&[
            b"<</Type /Catalog>>".to_vec(),
            b"<</Type /Font /Subtype /Type0 /BaseFont /AAAAAA+Font /ToUnicode 3 0 R>>".to_vec(),
            stream_object("", &write_cmap(&cmap(&[(0x03, " ")]))),
        ]);
        let texts = GlyphTexts::default().with_glyph("Other", 0x0332, "ffi");

        assert_eq!(fix_to_unicode(pdf.clone(), &texts), pdf);
    }

    /// EB Garamond forms standard ligatures (fi, fl, ffi, ...).
    const LIGATURE_FONT: &[u8] =
        include_bytes!("../../../../frontend/playwright/data/render-wasm/assets/ebgaramond.ttf");

    fn add_text(pool: &mut ShapesPool, id: Uuid, family: FontFamily, text: &str) {
        let mut content = TextContent::new(
            skia::Rect::from_xywh(0.0, 0.0, 600.0, 100.0),
            GrowType::Fixed,
        );
        let span = TextSpan::new(
            text.to_string(),
            family,
            40.0,
            1.2,
            0.0,
            None,
            None,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            vec![Fill::Solid(SolidColor(skia::Color::BLACK))],
        );
        content.add_paragraph(Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![span],
        ));
        let shape = pool.add_shape(id);
        shape.set_parent(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 600.0, 100.0);
        shape.set_shape_type(Type::Text(content));
    }

    /// The text a PDF reader copies from the page: every glyph code drawn,
    /// read through the font's final ToUnicode map.
    fn copied_text(pdf: &[u8]) -> String {
        let offsets = latest_offsets(pdf);
        let page = offsets
            .values()
            .filter_map(|offset| read_object(pdf, *offset))
            .find(|o| dict_name(o.dict, b"/Type") == Some(b"Page".as_slice()))
            .expect("page");
        let contents = dict_ref(page.dict, b"/Contents").expect("contents");
        let content = stream_data(&object(pdf, &offsets, contents)).expect("content");
        let to_unicode = to_unicode_of_only_font(pdf);
        CMapTokens {
            data: &content,
            pos: 0,
        }
        .filter_map(|token| match token {
            Token::Hex(codes) => Some(codes),
            _ => None,
        })
        .flat_map(|codes| {
            codes
                .chunks(2)
                .filter_map(to_code)
                .map(|code| String::from_utf16_lossy(&to_unicode[&code]))
                .collect::<Vec<_>>()
        })
        .collect()
    }

    #[test]
    fn exported_ligatures_copy_back_as_typed() {
        let text = "office fluffy affinity";
        let family = FontFamily::new(uuid_from_u32_quartet(0, 0, 0, 99), 400, FontStyle::Normal);
        let id = uuid_from_u32_quartet(0, 0, 0, 1);
        let mut pool = ShapesPool::new();
        add_text(&mut pool, id, family, text);

        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        resources
            .fonts
            .add(family, LIGATURE_FONT, false, false)
            .expect("ligature font");
        let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
        let pdf = super::super::render_to_pdf(&mut resources, &id, &pool).expect("pdf");

        let trailer = parse_xref(&pdf).expect("xref").trailer;
        assert!(
            dict_uint(&trailer, b"/Prev").is_some(),
            "the ligatures should need a ToUnicode fix"
        );
        assert_eq!(copied_text(&pdf), text);
    }
}
