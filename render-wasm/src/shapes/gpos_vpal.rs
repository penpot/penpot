//! Minimal `GPOS` parser for the `vpal` (proportional vertical alternate
//! metrics) feature. Vertical layout applies these deltas itself: cells
//! flow by `vmtx` advances, so HarfBuzz never gets a chance to apply
//! vertical GPOS positioning (SkShaper shapes on a horizontal line).
//!
//! Only single-adjustment lookups are read (SinglePos, directly or behind
//! an Extension lookup) — `vpal` is metrics-only by design and real fonts
//! (Noto CJK, Source Han) encode it exactly this way.

use std::collections::HashMap;

/// Font-unit deltas for one glyph. `y_placement` is y-up (positive lifts
/// the ink toward the flow start); `y_advance` is negative when the
/// vertical advance shrinks.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct VpalDelta {
    pub y_placement: i16,
    pub y_advance: i16,
}

fn read_u16(data: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes([
        *data.get(offset)?,
        *data.get(offset + 1)?,
    ]))
}

fn read_i16(data: &[u8], offset: usize) -> Option<i16> {
    read_u16(data, offset).map(|v| v as i16)
}

fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_be_bytes([
        *data.get(offset)?,
        *data.get(offset + 1)?,
        *data.get(offset + 2)?,
        *data.get(offset + 3)?,
    ]))
}

/// Glyph ids covered by a Coverage table, in coverage-index order.
fn parse_coverage(data: &[u8], offset: usize) -> Option<Vec<u16>> {
    match read_u16(data, offset)? {
        1 => {
            let count = read_u16(data, offset + 2)? as usize;
            (0..count)
                .map(|i| read_u16(data, offset + 4 + i * 2))
                .collect()
        }
        2 => {
            let range_count = read_u16(data, offset + 2)? as usize;
            let mut glyphs = Vec::new();
            for i in 0..range_count {
                let o = offset + 4 + i * 6;
                let start = read_u16(data, o)?;
                let end = read_u16(data, o + 2)?;
                if end < start {
                    return None;
                }
                glyphs.extend(start..=end);
            }
            Some(glyphs)
        }
        _ => None,
    }
}

/// A ValueRecord holds one i16 per set low bit of `value_format`, in bit
/// order (xPlacement, yPlacement, xAdvance, yAdvance, then four device
/// offsets). Returns the y deltas and consumes nothing else.
fn parse_value_record(data: &[u8], offset: usize, value_format: u16) -> Option<VpalDelta> {
    let mut delta = VpalDelta::default();
    let mut o = offset;
    for bit in 0..8 {
        if value_format & (1 << bit) == 0 {
            continue;
        }
        match bit {
            1 => delta.y_placement = read_i16(data, o)?,
            3 => delta.y_advance = read_i16(data, o)?,
            _ => {}
        }
        o += 2;
    }
    Some(delta)
}

fn value_record_size(value_format: u16) -> usize {
    (value_format & 0x00FF).count_ones() as usize * 2
}

/// Accumulate a SinglePos subtable into `deltas`.
fn parse_single_pos(
    data: &[u8],
    offset: usize,
    deltas: &mut HashMap<u16, VpalDelta>,
) -> Option<()> {
    let format = read_u16(data, offset)?;
    let coverage = parse_coverage(data, offset + read_u16(data, offset + 2)? as usize)?;
    let value_format = read_u16(data, offset + 4)?;
    match format {
        1 => {
            let value = parse_value_record(data, offset + 6, value_format)?;
            for glyph in coverage {
                let entry = deltas.entry(glyph).or_default();
                entry.y_placement += value.y_placement;
                entry.y_advance += value.y_advance;
            }
        }
        2 => {
            let count = read_u16(data, offset + 6)? as usize;
            let size = value_record_size(value_format);
            for (i, glyph) in coverage.into_iter().take(count).enumerate() {
                let value = parse_value_record(data, offset + 8 + i * size, value_format)?;
                let entry = deltas.entry(glyph).or_default();
                entry.y_placement += value.y_placement;
                entry.y_advance += value.y_advance;
            }
        }
        _ => {}
    }
    Some(())
}

/// Parse the `vpal` deltas out of a raw `GPOS` table. Returns `None` when
/// the table is malformed or carries no `vpal` feature.
pub fn parse_vpal(gpos: &[u8]) -> Option<HashMap<u16, VpalDelta>> {
    let feature_list = read_u16(gpos, 6)? as usize;
    let lookup_list = read_u16(gpos, 8)? as usize;

    // Collect the lookup indices of every feature tagged `vpal`,
    // regardless of script: the deltas are per-glyph metrics.
    let feature_count = read_u16(gpos, feature_list)? as usize;
    let mut lookup_indices = Vec::new();
    for i in 0..feature_count {
        let record = feature_list + 2 + i * 6;
        if gpos.get(record..record.checked_add(4)?)? != b"vpal" {
            continue;
        }
        let feature = feature_list + read_u16(gpos, record + 4)? as usize;
        let index_count = read_u16(gpos, feature + 2)? as usize;
        for j in 0..index_count {
            lookup_indices.push(read_u16(gpos, feature + 4 + j * 2)? as usize);
        }
    }
    lookup_indices.sort_unstable();
    lookup_indices.dedup();
    if lookup_indices.is_empty() {
        return None;
    }

    let lookup_count = read_u16(gpos, lookup_list)? as usize;
    let mut deltas = HashMap::new();
    for index in lookup_indices {
        if index >= lookup_count {
            continue;
        }
        let lookup = lookup_list + read_u16(gpos, lookup_list + 2 + index * 2)? as usize;
        let lookup_type = read_u16(gpos, lookup)?;
        let subtable_count = read_u16(gpos, lookup + 4)? as usize;
        for s in 0..subtable_count {
            let subtable = lookup + read_u16(gpos, lookup + 6 + s * 2)? as usize;
            match lookup_type {
                1 => {
                    parse_single_pos(gpos, subtable, &mut deltas)?;
                }
                9 => {
                    // ExtensionPos: { format: u16, extensionLookupType: u16,
                    // extensionOffset: u32 (from the subtable start) }.
                    if read_u16(gpos, subtable + 2)? == 1 {
                        let inner = subtable + read_u32(gpos, subtable + 4)? as usize;
                        parse_single_pos(gpos, inner, &mut deltas)?;
                    }
                }
                _ => {}
            }
        }
    }
    if deltas.is_empty() {
        None
    } else {
        Some(deltas)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Subset of Noto Sans JP: 〱あく「」、。 with `vert` alternates and the
    // real `vpal` deltas (Extension→SinglePos, both coverage and both
    // SinglePos formats, value formats 0x8 and 0xA).
    const VPAL_TEST_FONT: &[u8] = include_bytes!("../fonts/notosansjp-vpal-test.ttf");

    fn gpos_table() -> Vec<u8> {
        // Locate the GPOS table in the raw sfnt directory.
        let data = VPAL_TEST_FONT;
        let num_tables = u16::from_be_bytes([data[4], data[5]]) as usize;
        for i in 0..num_tables {
            let record = 12 + i * 16;
            if &data[record..record + 4] == b"GPOS" {
                let offset = u32::from_be_bytes([
                    data[record + 8],
                    data[record + 9],
                    data[record + 10],
                    data[record + 11],
                ]) as usize;
                let length = u32::from_be_bytes([
                    data[record + 12],
                    data[record + 13],
                    data[record + 14],
                    data[record + 15],
                ]) as usize;
                return data[offset..offset + length].to_vec();
            }
        }
        panic!("test font has no GPOS table");
    }

    #[test]
    fn parses_noto_vpal_deltas() {
        let deltas = parse_vpal(&gpos_table()).expect("vpal deltas");
        // Vertical alternates: 、→8 。→9 「→10 」→11 あ→12 く→13.
        assert_eq!(
            deltas.get(&8),
            Some(&VpalDelta {
                y_placement: 0,
                y_advance: -500
            }),
            "、 halves its vertical advance"
        );
        assert_eq!(
            deltas.get(&10),
            Some(&VpalDelta {
                y_placement: 481,
                y_advance: -500
            }),
            "「 lifts its trailing-half ink into the compressed cell"
        );
        assert_eq!(
            deltas.get(&12),
            Some(&VpalDelta {
                y_placement: 39,
                y_advance: -58
            }),
            "あ tightens slightly"
        );
        // Base (horizontal) glyphs carry no vpal.
        assert!(!deltas.contains_key(&6), "base あ glyph is uncovered");
    }

    #[test]
    fn missing_vpal_returns_none() {
        // A GPOS header with an empty feature list.
        let gpos = [
            0x00, 0x01, 0x00, 0x00, // version 1.0
            0x00, 0x0A, // scriptList
            0x00, 0x0C, // featureList
            0x00, 0x0E, // lookupList
            0x00, 0x00, // scriptCount
            0x00, 0x00, // featureCount
            0x00, 0x00, // lookupCount
        ];
        assert!(parse_vpal(&gpos).is_none());
    }

    #[test]
    fn truncated_table_is_rejected() {
        let table = gpos_table();
        for len in [0usize, 4, 9, 16] {
            assert!(parse_vpal(&table[..len.min(table.len())]).is_none());
        }
    }

    #[test]
    fn truncated_feature_records_are_rejected() {
        let gpos = [
            0x00, 0x01, 0x00, 0x00, // version 1.0
            0x00, 0x0A, // scriptList
            0x00, 0x0A, // featureList
            0x00, 0x0A, // lookupList
            0x00, 0x02, // featureCount without matching records
        ];

        assert!(parse_vpal(&gpos).is_none());
    }
}
