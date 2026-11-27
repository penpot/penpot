use crate::shapes::{Paragraph, TextContent, TextDirection, TextPositionWithAffinity, TextSpan};
use crate::state::TextSelection;

/// Get total character count in a paragraph.
pub fn paragraph_char_count(para: &Paragraph) -> usize {
    para.children()
        .iter()
        .map(|span| span.text.chars().count())
        .sum()
}

/// Get the text direction of the span at a given offset in a paragraph.
pub fn get_text_span_text_direction_at_offset(
    para: &Paragraph,
    char_offset: usize,
) -> TextDirection {
    if let Some((span_idx, _)) = find_text_span_at_offset(para, char_offset) {
        if let Some(span) = para.children().get(span_idx) {
            return span.text_direction;
        }
    }
    // Fallback to paragraph's text direction
    para.text_direction()
}

/// Clamp a cursor position to valid bounds within the text content.
pub fn clamp_cursor(
    position: TextPositionWithAffinity,
    paragraphs: &[Paragraph],
) -> TextPositionWithAffinity {
    if paragraphs.is_empty() {
        return TextPositionWithAffinity::new_downstream_affinity(0, 0);
    }

    let para_idx = position.paragraph.min(paragraphs.len() - 1);
    let para_len = paragraph_char_count(&paragraphs[para_idx]);
    let char_offset = position.offset.min(para_len);

    TextPositionWithAffinity::new_downstream_affinity(para_idx, char_offset)
}

/// Move cursor left by one character.
pub fn move_cursor_backward(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    word_boundary: bool,
) -> TextPositionWithAffinity {
    if !word_boundary {
        if cursor.offset > 0 {
            return TextPositionWithAffinity::new_downstream_affinity(
                cursor.paragraph,
                paragraphs[cursor.paragraph].prev_grapheme_offset(cursor.offset),
            );
        }
        if cursor.paragraph > 0 {
            let prev_para = cursor.paragraph - 1;
            let char_count = paragraph_char_count(&paragraphs[prev_para]);
            return TextPositionWithAffinity::new_downstream_affinity(prev_para, char_count);
        }
        return *cursor;
    }

    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return *cursor;
    }

    let end_para = cursor.paragraph;
    let end_offset = cursor
        .offset
        .min(paragraph_char_count(&paragraphs[end_para]));

    let mut para_idx = end_para;
    let mut offset = end_offset;
    let mut phase = 0u8;

    loop {
        let current = if offset > 0 {
            paragraph_text_char_at(&paragraphs[para_idx], offset - 1)
        } else if para_idx > 0 {
            Some('\n')
        } else {
            None
        };

        let Some(ch) = current else {
            break;
        };

        if offset > 0 {
            offset -= 1;
        } else {
            para_idx -= 1;
            offset = paragraph_char_count(&paragraphs[para_idx]);
        }

        if phase == 0 {
            if is_word_char(ch) {
                phase = 1;
            }
            continue;
        }

        if !is_word_char(ch) {
            if offset < paragraph_char_count(&paragraphs[para_idx]) {
                offset += 1;
            } else if para_idx < end_para || offset < end_offset {
                para_idx += 1;
                offset = 0;
            }
            break;
        }
    }

    TextPositionWithAffinity::new_downstream_affinity(para_idx, offset)
}

/// Move cursor right by one character.
pub fn move_cursor_forward(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    word_boundary: bool,
) -> TextPositionWithAffinity {
    if !word_boundary {
        let para = &paragraphs[cursor.paragraph];
        let char_count = paragraph_char_count(para);
        if cursor.offset < char_count {
            return TextPositionWithAffinity::new_downstream_affinity(
                cursor.paragraph,
                para.next_grapheme_offset(cursor.offset),
            );
        }
        if cursor.paragraph < paragraphs.len() - 1 {
            return TextPositionWithAffinity::new_downstream_affinity(cursor.paragraph + 1, 0);
        }
        return *cursor;
    }

    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return *cursor;
    }

    let mut para_idx = cursor.paragraph;
    let mut offset = cursor
        .offset
        .min(paragraph_char_count(&paragraphs[para_idx]));
    let mut phase = 0u8;

    loop {
        let para = &paragraphs[para_idx];
        let para_len = paragraph_char_count(para);

        let current = if offset < para_len {
            paragraph_text_char_at(para, offset)
        } else if para_idx < paragraphs.len() - 1 {
            Some('\n')
        } else {
            None
        };

        let Some(ch) = current else {
            break;
        };

        if phase == 0 {
            if is_word_char(ch) {
                phase = 1;
            } else if offset < para_len {
                offset += 1;
            } else {
                para_idx += 1;
                offset = 0;
            }
            continue;
        }

        if is_word_char(ch) {
            if offset < para_len {
                offset += 1;
            } else {
                para_idx += 1;
                offset = 0;
            }
        } else {
            break;
        }
    }

    TextPositionWithAffinity::new_downstream_affinity(para_idx, offset)
}

/// Move cursor to the paragraph before, keeping the offset. Fallback of
/// `caret_line_step` for text without a layout.
pub fn move_cursor_up(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
) -> TextPositionWithAffinity {
    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return *cursor;
    }

    if cursor.paragraph > 0 {
        let prev_para = cursor.paragraph - 1;
        let char_count = paragraph_char_count(&paragraphs[prev_para]);
        let new_offset = cursor.offset.min(char_count);
        TextPositionWithAffinity::new_downstream_affinity(prev_para, new_offset)
    } else {
        TextPositionWithAffinity::new_downstream_affinity(cursor.paragraph, 0)
    }
}

/// Move cursor to the paragraph after, keeping the offset. Fallback of
/// `caret_line_step` for text without a layout.
pub fn move_cursor_down(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
) -> TextPositionWithAffinity {
    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return *cursor;
    }

    if cursor.paragraph < paragraphs.len() - 1 {
        let next_para = cursor.paragraph + 1;
        let char_count = paragraph_char_count(&paragraphs[next_para]);
        let new_offset = cursor.offset.min(char_count);
        TextPositionWithAffinity::new_downstream_affinity(next_para, new_offset)
    } else {
        let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
        TextPositionWithAffinity::new_downstream_affinity(cursor.paragraph, char_count)
    }
}

/// Move cursor to the start of its paragraph. Fallback of `caret_line_edge`
/// for text without a layout.
pub fn move_cursor_line_start(
    cursor: &TextPositionWithAffinity,
    _paragraphs: &[Paragraph],
) -> TextPositionWithAffinity {
    TextPositionWithAffinity::new_downstream_affinity(cursor.paragraph, 0)
}

/// Move cursor to the end of its paragraph. Fallback of `caret_line_edge`
/// for text without a layout, and the target of Ctrl+End.
pub fn move_cursor_line_end(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
) -> TextPositionWithAffinity {
    let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
    TextPositionWithAffinity::new_downstream_affinity(cursor.paragraph, char_count)
}

pub fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

pub fn paragraph_text_char_at(para: &Paragraph, offset: usize) -> Option<char> {
    let mut remaining = offset;
    for span in para.children() {
        let span_len = span.text.chars().count();
        if remaining < span_len {
            return span.text.chars().nth(remaining);
        }
        remaining -= span_len;
    }
    None
}

pub fn find_text_span_at_offset(para: &Paragraph, char_offset: usize) -> Option<(usize, usize)> {
    let children = para.children();
    let mut accumulated = 0;
    for (span_idx, span) in children.iter().enumerate() {
        let span_len = span.text.chars().count();
        if char_offset <= accumulated + span_len {
            return Some((span_idx, char_offset - accumulated));
        }
        accumulated += span_len;
    }
    if !children.is_empty() {
        let last_idx = children.len() - 1;
        let last_len = children[last_idx].text.chars().count();
        return Some((last_idx, last_len));
    }
    None
}

pub fn replace_text_with_newlines(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
    text: &str,
) -> Option<TextPositionWithAffinity> {
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.split('\n').collect();
    if lines.is_empty() {
        return None;
    }

    let mut current_cursor = *cursor;

    let new_offset = replace_text_at_cursor(text_content, &current_cursor, lines[0])?;
    current_cursor =
        TextPositionWithAffinity::new_downstream_affinity(current_cursor.paragraph, new_offset);

    for line in lines.iter().skip(1) {
        if !split_paragraph_at_cursor(text_content, &current_cursor) {
            break;
        }
        current_cursor =
            TextPositionWithAffinity::new_downstream_affinity(current_cursor.paragraph + 1, 0);
        if let Some(new_offset) = replace_text_at_cursor(text_content, &current_cursor, line) {
            current_cursor = TextPositionWithAffinity::new_downstream_affinity(
                current_cursor.paragraph,
                new_offset,
            );
        }
    }

    Some(current_cursor)
}

/// Insert text at a cursor position, splitting on newlines into multiple paragraphs.
/// Returns the final cursor position after insertion.
pub fn insert_text_with_newlines(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
    text: &str,
) -> Option<TextPositionWithAffinity> {
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.split('\n').collect();
    if lines.is_empty() {
        return None;
    }

    let mut current_cursor = *cursor;

    let new_offset = insert_text_at_cursor(text_content, &current_cursor, lines[0])?;
    current_cursor =
        TextPositionWithAffinity::new_downstream_affinity(current_cursor.paragraph, new_offset);

    for line in lines.iter().skip(1) {
        if !split_paragraph_at_cursor(text_content, &current_cursor) {
            break;
        }
        current_cursor =
            TextPositionWithAffinity::new_downstream_affinity(current_cursor.paragraph + 1, 0);
        if let Some(new_offset) = insert_text_at_cursor(text_content, &current_cursor, line) {
            current_cursor = TextPositionWithAffinity::new_downstream_affinity(
                current_cursor.paragraph,
                new_offset,
            );
        }
    }

    Some(current_cursor)
}

/// Insert text at a cursor position. Returns the new character offset after insertion.
pub fn insert_text_at_cursor(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
    text: &str,
) -> Option<usize> {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let para = &mut paragraphs[cursor.paragraph];

    let children = para.children_mut();
    if children.is_empty() {
        return None;
    }

    if children.len() == 1 && children[0].text.is_empty() {
        children[0].drop_annotations();
        children[0].set_text(text.to_string());
        return Some(text.chars().count());
    }

    let (span_idx, offset_in_span) = insertion_point(para, cursor.offset)?;

    let children = para.children_mut();
    let span = &mut children[span_idx];
    let mut new_text = span.text.clone();

    let byte_offset = new_text
        .char_indices()
        .nth(offset_in_span)
        .map(|(i, _)| i)
        .unwrap_or(new_text.len());

    new_text.insert_str(byte_offset, text);
    span.set_text(new_text);

    Some(cursor.offset + text.chars().count())
}

/// Span and offset in it where text typed at `char_offset` goes. A reading or
/// warichu note belongs to the text it annotates, so typed text joins it only
/// inside its base: at either edge it goes into the unannotated neighbour on
/// that side, or into a new unannotated span with the same style.
pub fn insertion_point(para: &mut Paragraph, char_offset: usize) -> Option<(usize, usize)> {
    let (span_idx, offset_in_span) = find_text_span_at_offset(para, char_offset)?;
    let children = para.children_mut();
    let span = &children[span_idx];
    let span_len = span.text.chars().count();

    if !span.is_annotated() || (offset_in_span > 0 && offset_in_span < span_len) {
        return Some((span_idx, offset_in_span));
    }

    if span_len == 0 {
        children[span_idx].drop_annotations();
        return Some((span_idx, 0));
    }

    if offset_in_span == span_len {
        return match children.get(span_idx + 1) {
            Some(next) if next.shares_ruby_base_with(span) => Some((span_idx, offset_in_span)),
            Some(next) if !next.is_annotated() => Some((span_idx + 1, 0)),
            _ => {
                let plain = span.unannotated_copy();
                children.insert(span_idx + 1, plain);
                Some((span_idx + 1, 0))
            }
        };
    }

    match span_idx.checked_sub(1).map(|idx| &children[idx]) {
        Some(previous) if span.shares_ruby_base_with(previous) => Some((span_idx, 0)),
        Some(previous) if !previous.is_annotated() => {
            Some((span_idx - 1, previous.text.chars().count()))
        }
        _ => {
            let plain = span.unannotated_copy();
            children.insert(span_idx, plain);
            Some((span_idx, 0))
        }
    }
}

/// Replace text at cursor position (overtype mode). Replaces N characters where N is the
/// length of the input text, returning the new cursor offset.
pub fn replace_text_at_cursor(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
    text: &str,
) -> Option<usize> {
    let text_len = text.chars().count();
    if text_len == 0 {
        return Some(cursor.offset);
    }

    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let para = &mut paragraphs[cursor.paragraph];
    let children = para.children_mut();
    if children.is_empty() {
        return None;
    }

    if children.len() == 1 && children[0].text.is_empty() {
        children[0].drop_annotations();
        children[0].set_text(text.to_string());
        return Some(text_len);
    }

    // At the end of a span there is nothing of it to replace, so the text
    // goes where an insertion would.
    let (span_idx, offset_in_span) = match find_text_span_at_offset(para, cursor.offset)? {
        (idx, offset) if offset == para.children()[idx].text.chars().count() => {
            insertion_point(para, cursor.offset)?
        }
        found => found,
    };

    let children = para.children_mut();
    let span = &mut children[span_idx];
    let mut new_text = span.text.clone();

    let byte_offset = new_text
        .char_indices()
        .nth(offset_in_span)
        .map(|(i, _)| i)
        .unwrap_or(new_text.len());

    let end_byte_offset = new_text
        .char_indices()
        .nth(offset_in_span + text_len)
        .map(|(i, _)| i)
        .unwrap_or(new_text.len());

    new_text.replace_range(byte_offset..end_byte_offset, text);
    span.set_text(new_text);

    Some(cursor.offset + text_len)
}

/// Delete a range of text specified by a selection.
pub fn delete_selection_range(text_content: &mut TextContent, selection: &TextSelection) {
    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs_mut();
    if paragraphs.is_empty() || start.paragraph >= paragraphs.len() {
        return;
    }

    let end_paragraph = end.paragraph.min(paragraphs.len() - 1);

    if start.paragraph == end_paragraph {
        delete_range_in_paragraph(&mut paragraphs[start.paragraph], start.offset, end.offset);
    } else {
        let start_para_len = paragraph_char_count(&paragraphs[start.paragraph]);
        delete_range_in_paragraph(
            &mut paragraphs[start.paragraph],
            start.offset,
            start_para_len,
        );

        delete_range_in_paragraph(&mut paragraphs[end_paragraph], 0, end.offset);

        let end_para_children: Vec<_> = std::mem::take(paragraphs[end_paragraph].children_mut());
        paragraphs[start.paragraph].extend_children(end_para_children);

        paragraphs.drain((start.paragraph + 1)..=end_paragraph);

        drop_empty_spans(paragraphs[start.paragraph].children_mut());
    }
}

/// Removes the empty spans of a paragraph. One that is left with no text
/// keeps its first span as a placeholder, without its reading or warichu
/// note: the text typed next takes its style but not those.
fn drop_empty_spans(children: &mut Vec<TextSpan>) {
    if children.iter().any(|span| !span.text.is_empty()) {
        children.retain(|span| !span.text.is_empty());
    } else if let Some(placeholder) = children.first_mut() {
        placeholder.drop_annotations();
        children.truncate(1);
    }
}

/// Delete a range of characters within a single paragraph.
pub fn delete_range_in_paragraph(para: &mut Paragraph, start_offset: usize, end_offset: usize) {
    // An out of bounds offset must not skip the deletion.
    let para_len = paragraph_char_count(para);
    let start_offset = start_offset.min(para_len);
    let end_offset = end_offset.min(para_len);

    if start_offset >= end_offset {
        return;
    }

    let mut accumulated = 0;
    let mut delete_start_span = None;
    let mut delete_end_span = None;

    for (idx, span) in para.children().iter().enumerate() {
        let span_len = span.text.chars().count();
        let span_end = accumulated + span_len;

        if delete_start_span.is_none() && start_offset < span_end {
            delete_start_span = Some((idx, start_offset - accumulated));
        }
        if end_offset <= span_end {
            delete_end_span = Some((idx, end_offset - accumulated));
            break;
        }
        accumulated += span_len;
    }

    let Some((start_span_idx, start_in_span)) = delete_start_span else {
        return;
    };
    let Some((end_span_idx, end_in_span)) = delete_end_span else {
        return;
    };

    let children = para.children_mut();

    if start_span_idx == end_span_idx {
        let span = &mut children[start_span_idx];
        let text = span.text.clone();
        let chars: Vec<char> = text.chars().collect();

        let start_clamped = start_in_span.min(chars.len());
        let end_clamped = end_in_span.min(chars.len());

        let new_text: String = chars[..start_clamped]
            .iter()
            .chain(chars[end_clamped..].iter())
            .collect();
        span.set_text(new_text);
    } else {
        let start_span = &mut children[start_span_idx];
        let text = start_span.text.clone();
        let start_char_count = text.chars().count();
        let start_clamped = start_in_span.min(start_char_count);
        let new_text: String = text.chars().take(start_clamped).collect();
        start_span.set_text(new_text);

        let end_span = &mut children[end_span_idx];
        let text = end_span.text.clone();
        let end_char_count = text.chars().count();
        let end_clamped = end_in_span.min(end_char_count);
        let new_text: String = text.chars().skip(end_clamped).collect();
        end_span.set_text(new_text);

        if end_span_idx > start_span_idx + 1 {
            children.drain((start_span_idx + 1)..end_span_idx);
        }
    }

    drop_empty_spans(children);
}

/// Delete the character before the cursor. Returns the new cursor position.
pub fn delete_char_before(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
) -> Option<TextPositionWithAffinity> {
    if cursor.offset > 0 {
        let paragraphs = text_content.paragraphs_mut();
        let para = &mut paragraphs[cursor.paragraph];
        let delete_pos = para.prev_grapheme_offset(cursor.offset);
        delete_range_in_paragraph(para, delete_pos, cursor.offset);
        Some(TextPositionWithAffinity::new_downstream_affinity(
            cursor.paragraph,
            delete_pos,
        ))
    } else if cursor.paragraph > 0 {
        let prev_para_idx = cursor.paragraph - 1;
        let paragraphs = text_content.paragraphs_mut();
        let prev_para_len = paragraph_char_count(&paragraphs[prev_para_idx]);

        let current_children: Vec<_> = std::mem::take(paragraphs[cursor.paragraph].children_mut());
        paragraphs[prev_para_idx].extend_children(current_children);

        paragraphs.remove(cursor.paragraph);

        Some(TextPositionWithAffinity::new_downstream_affinity(
            prev_para_idx,
            prev_para_len,
        ))
    } else {
        None
    }
}

/// Delete the word before the cursor. Returns the new cursor position.
pub fn delete_word_before(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
) -> Option<TextPositionWithAffinity> {
    let paragraphs = text_content.paragraphs();
    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return None;
    }

    let end_paragraph = cursor.paragraph;
    let end_offset = cursor
        .offset
        .min(paragraph_char_count(&paragraphs[end_paragraph]));

    let mut start_paragraph = end_paragraph;
    let mut start_offset = end_offset;

    let mut phase = 0u8;
    loop {
        let current = if start_offset > 0 {
            let para = &paragraphs[start_paragraph];
            paragraph_text_char_at(para, start_offset - 1)
        } else if start_paragraph > 0 {
            Some('\n')
        } else {
            None
        };

        let Some(ch) = current else {
            break;
        };

        if start_offset > 0 {
            start_offset -= 1;
        } else {
            start_paragraph -= 1;
            start_offset = paragraph_char_count(&paragraphs[start_paragraph]);
        }

        if phase == 0 {
            if is_word_char(ch) {
                phase = 1;
            }
            continue;
        }

        if !is_word_char(ch) {
            if start_offset < paragraph_char_count(&paragraphs[start_paragraph]) {
                start_offset += 1;
            } else if start_paragraph < end_paragraph || start_offset < end_offset {
                start_paragraph += 1;
                start_offset = 0;
            }
            break;
        }
    }

    if start_paragraph == end_paragraph && start_offset == end_offset {
        return None;
    }

    let selection = TextSelection {
        anchor: TextPositionWithAffinity::new_downstream_affinity(start_paragraph, start_offset),
        focus: TextPositionWithAffinity::new_downstream_affinity(end_paragraph, end_offset),
    };

    delete_selection_range(text_content, &selection);

    Some(TextPositionWithAffinity::new_downstream_affinity(
        start_paragraph,
        start_offset,
    ))
}

/// Delete the word after the cursor.
pub fn delete_word_after(text_content: &mut TextContent, cursor: &TextPositionWithAffinity) {
    let paragraphs = text_content.paragraphs();
    if paragraphs.is_empty() || cursor.paragraph >= paragraphs.len() {
        return;
    }

    let start_paragraph = cursor.paragraph;
    let start_offset = cursor
        .offset
        .min(paragraph_char_count(&paragraphs[start_paragraph]));

    let mut end_paragraph = start_paragraph;
    let mut end_offset = start_offset;

    let mut phase = 0u8;
    loop {
        let para = &paragraphs[end_paragraph];
        let para_len = paragraph_char_count(para);

        let current = if end_offset < para_len {
            paragraph_text_char_at(para, end_offset)
        } else if end_paragraph < paragraphs.len() - 1 {
            Some('\n')
        } else {
            None
        };

        let Some(ch) = current else {
            break;
        };

        if phase == 0 {
            if is_word_char(ch) {
                phase = 1;
            } else if end_offset < para_len {
                end_offset += 1;
            } else {
                end_paragraph += 1;
                end_offset = 0;
            }
            continue;
        }

        if is_word_char(ch) {
            if end_offset < para_len {
                end_offset += 1;
            } else {
                end_paragraph += 1;
                end_offset = 0;
            }
        } else {
            break;
        }
    }

    if start_paragraph == end_paragraph && start_offset == end_offset {
        return;
    }

    let selection = TextSelection {
        anchor: TextPositionWithAffinity::new_downstream_affinity(start_paragraph, start_offset),
        focus: TextPositionWithAffinity::new_downstream_affinity(end_paragraph, end_offset),
    };

    delete_selection_range(text_content, &selection);
}

/// Delete the character after the cursor.
pub fn delete_char_after(text_content: &mut TextContent, cursor: &TextPositionWithAffinity) {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return;
    }

    let para_len = paragraph_char_count(&paragraphs[cursor.paragraph]);

    if cursor.offset < para_len {
        let para = &mut paragraphs[cursor.paragraph];
        let end = para.next_grapheme_offset(cursor.offset);
        delete_range_in_paragraph(para, cursor.offset, end);
    } else if cursor.paragraph < paragraphs.len() - 1 {
        let next_para_idx = cursor.paragraph + 1;
        let next_children: Vec<_> = std::mem::take(paragraphs[next_para_idx].children_mut());
        paragraphs[cursor.paragraph].extend_children(next_children);

        paragraphs.remove(next_para_idx);
    }
}

/// Spans of the ruby base whose text the character offset `offset` falls
/// strictly inside of, if any.
fn ruby_base_cut_at(para: &Paragraph, offset: usize) -> Option<std::ops::Range<usize>> {
    let mut starts = Vec::with_capacity(para.children().len() + 1);
    let mut position = 0;
    for span in para.children() {
        starts.push(position);
        position += span.text.chars().count();
    }
    starts.push(position);
    para.ruby_groups()
        .into_iter()
        .find(|group| starts[group.start] < offset && offset < starts[group.end])
}

/// Split a paragraph at the cursor position. Returns true if split was successful.
/// A reading cannot cover two paragraphs: splitting its base drops it.
pub fn split_paragraph_at_cursor(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
) -> bool {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return false;
    }

    if let Some(group) = ruby_base_cut_at(&paragraphs[cursor.paragraph], cursor.offset) {
        for span in &mut paragraphs[cursor.paragraph].children_mut()[group] {
            span.drop_annotations();
        }
    }

    let para = &paragraphs[cursor.paragraph];

    let Some((span_idx, offset_in_span)) = find_text_span_at_offset(para, cursor.offset) else {
        return false;
    };

    let mut new_para_children = Vec::new();
    let children = para.children();

    let current_span = &children[span_idx];
    let span_text = current_span.text.clone();
    let chars: Vec<char> = span_text.chars().collect();

    if offset_in_span < chars.len() {
        let after_text: String = chars[offset_in_span..].iter().collect();
        let mut new_span = current_span.clone();
        new_span.set_text(after_text);
        new_para_children.push(new_span);
    }

    for child in children.iter().skip(span_idx + 1) {
        new_para_children.push(child.clone());
    }

    if new_para_children.is_empty() {
        new_para_children.push(current_span.unannotated_copy());
    }

    let text_align = para.text_align();
    let text_direction = para.text_direction();
    let text_decoration = para.text_decoration();
    let text_transform = para.text_transform();
    let writing_mode = para.writing_mode();
    let text_orientation = para.text_orientation();
    let language = para.language().to_owned();
    let korean_wrap = para.korean_wrap();
    let source_position = para.source_position();
    let line_height = para.line_height();
    let letter_spacing = para.letter_spacing();

    let para = &mut paragraphs[cursor.paragraph];
    let children = para.children_mut();

    children.truncate(span_idx + 1);

    if !children.is_empty() {
        let span = &mut children[span_idx];
        let text = span.text.clone();
        let new_text: String = text.chars().take(offset_in_span).collect();
        span.set_text(new_text);
    }
    drop_empty_spans(children);

    let mut new_para = crate::shapes::Paragraph::new(
        text_align,
        text_direction,
        text_decoration,
        text_transform,
        line_height,
        letter_spacing,
        new_para_children,
    );
    new_para.set_writing_mode(writing_mode);
    new_para.set_text_orientation(text_orientation);
    new_para.set_language(language);
    new_para.set_korean_wrap(korean_wrap);
    new_para.set_source_position(source_position);

    paragraphs.insert(cursor.paragraph + 1, new_para);

    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::math::Rect;
    use crate::shapes::{FontFamily, FontStyle, GrowType, TextAlign, TextSpan};
    use crate::uuid::Uuid;

    #[test]
    fn paragraph_split_keeps_language_and_korean_wrap() {
        for wrap in [
            crate::shapes::text_policy::KoreanWrap::SpacesOnly,
            crate::shapes::text_policy::KoreanWrap::KeepWords,
        ] {
            let mut para = paragraph(&["한국어"]);
            para.set_language("ko".to_string());
            para.set_korean_wrap(wrap);
            let mut content = content(vec![para]);
            let cursor = TextPositionWithAffinity {
                paragraph: 0,
                offset: 1,
                ..Default::default()
            };
            assert!(split_paragraph_at_cursor(&mut content, &cursor));
            assert_eq!(content.paragraphs().len(), 2);
            for para in content.paragraphs() {
                assert_eq!(para.language(), "ko");
                assert_eq!(para.korean_wrap(), wrap);
                assert_eq!(para.source_position(), 0);
                assert!(para.children().iter().all(|span| span.language == "ko"));
            }
        }
    }

    #[test]
    fn paragraph_merge_inherits_the_surviving_paragraph_language() {
        let mut first = paragraph(&["中"]);
        first.set_language("ja".to_string());
        let mut second = paragraph(&["中"]);
        second.set_language("ko".to_string());
        let mut content = content(vec![first, second]);
        delete_char_after(
            &mut content,
            &TextPositionWithAffinity {
                paragraph: 0,
                offset: 1,
                ..Default::default()
            },
        );
        assert_eq!(content.paragraphs().len(), 1);
        let paragraph = &content.paragraphs()[0];
        assert_eq!(paragraph.language(), "ja");
        assert!(paragraph
            .children()
            .iter()
            .all(|span| span.language == "ja"));
    }

    fn span(text: &str) -> TextSpan {
        TextSpan::new(
            text.to_string(),
            FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
            14.0,
            1.2,
            0.0,
            None,
            None,
            TextDirection::LTR,
            400,
            Uuid::nil(),
            vec![],
        )
    }

    fn paragraph(texts: &[&str]) -> Paragraph {
        Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            texts.iter().copied().map(span).collect(),
        )
    }

    fn content(paragraphs: Vec<Paragraph>) -> TextContent {
        let mut content =
            TextContent::new(Rect::from_xywh(0.0, 0.0, 100.0, 100.0), GrowType::Fixed);
        for para in paragraphs {
            content.add_paragraph(para);
        }
        content
    }

    fn text_of(content: &TextContent) -> String {
        content
            .paragraphs()
            .iter()
            .map(|para| {
                para.children()
                    .iter()
                    .map(|span| span.text.as_str())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn selection(start: (usize, usize), end: (usize, usize)) -> TextSelection {
        TextSelection {
            anchor: TextPositionWithAffinity::new_downstream_affinity(start.0, start.1),
            focus: TextPositionWithAffinity::new_downstream_affinity(end.0, end.1),
        }
    }

    #[test]
    fn delete_selection_range_deletes_a_whole_multibyte_paragraph() {
        let mut content = content(vec![paragraph(&["Añadir"])]);
        delete_selection_range(&mut content, &selection((0, 0), (0, 6)));
        assert_eq!(text_of(&content), "");
    }

    #[test]
    fn delete_range_in_paragraph_clamps_an_overshooting_end_offset() {
        let mut para = paragraph(&["Añadir"]);
        delete_range_in_paragraph(&mut para, 0, 7);
        assert_eq!(para.children().len(), 1);
        assert_eq!(para.children()[0].text, "");
    }

    #[test]
    fn delete_selection_range_deletes_emoji() {
        let mut content = content(vec![paragraph(&["Hi 😀"])]);
        delete_selection_range(&mut content, &selection((0, 0), (0, 4)));
        assert_eq!(text_of(&content), "");
    }

    #[test]
    fn delete_selection_range_never_splits_an_emoji() {
        let mut content = content(vec![paragraph(&["a😀b"])]);
        delete_selection_range(&mut content, &selection((0, 1), (0, 2)));
        assert_eq!(text_of(&content), "ab");
    }

    fn caret(paragraph: usize, offset: usize) -> TextPositionWithAffinity {
        TextPositionWithAffinity::new_downstream_affinity(paragraph, offset)
    }

    #[test]
    fn delete_char_before_deletes_a_whole_emoji() {
        for emoji in ["😀", "👍🏽", "👨\u{200d}👩\u{200d}👧", "🇪🇸"] {
            let text = format!("A{emoji}");
            let len = text.chars().count();
            let mut content = content(vec![paragraph(&[text.as_str()])]);
            let new_caret = delete_char_before(&mut content, &caret(0, len));
            assert_eq!(text_of(&content), "A");
            assert_eq!(new_caret, Some(caret(0, 1)));
        }
    }

    #[test]
    fn delete_char_after_deletes_a_whole_emoji() {
        let mut content = content(vec![paragraph(&["A👍🏽B"])]);
        delete_char_after(&mut content, &caret(0, 1));
        assert_eq!(text_of(&content), "AB");
    }

    #[test]
    fn move_cursor_steps_over_a_whole_emoji() {
        let paragraphs = vec![paragraph(&["A👍🏽B"])];
        let forward = move_cursor_forward(&caret(0, 1), &paragraphs, false);
        assert_eq!(forward, caret(0, 3));
        let backward = move_cursor_backward(&caret(0, 3), &paragraphs, false);
        assert_eq!(backward, caret(0, 1));
    }

    #[test]
    fn delete_selection_range_spanning_several_spans() {
        let mut content = content(vec![paragraph(&["Añ", "adir"])]);
        delete_selection_range(&mut content, &selection((0, 1), (0, 4)));
        assert_eq!(text_of(&content), "Air");
    }

    #[test]
    fn delete_selection_range_across_paragraphs() {
        let mut content = content(vec![
            paragraph(&["Añadir"]),
            paragraph(&["Más"]),
            paragraph(&["Fin"]),
        ]);
        delete_selection_range(&mut content, &selection((0, 0), (2, 3)));
        assert_eq!(content.paragraphs().len(), 1);
        assert_eq!(text_of(&content), "");
    }

    #[test]
    fn delete_selection_range_clamps_an_out_of_range_focus() {
        let mut content = content(vec![paragraph(&["Añadir"])]);
        delete_selection_range(&mut content, &selection((0, 0), (5, 99)));
        assert_eq!(text_of(&content), "");
    }

    fn reading(text: &str, ruby: &str) -> TextSpan {
        TextSpan {
            ruby: ruby.to_string(),
            ..span(text)
        }
    }

    /// Text and reading of each span of paragraph `index`.
    fn spans_of(content: &TextContent, index: usize) -> Vec<(String, String)> {
        content.paragraphs()[index]
            .children()
            .iter()
            .map(|span| (span.text.clone(), span.ruby.clone()))
            .collect()
    }

    fn pairs(expected: &[(&str, &str)]) -> Vec<(String, String)> {
        expected
            .iter()
            .map(|(text, ruby)| (text.to_string(), ruby.to_string()))
            .collect()
    }

    fn type_at(content: &mut TextContent, paragraph: usize, offset: usize, text: &str) {
        insert_text_with_newlines(content, &caret(paragraph, offset), text);
    }

    #[test]
    fn text_typed_over_a_whole_reading_drops_it() {
        let mut content = content(vec![Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![reading("漢字", "かんじ"), span("です")],
        )]);
        delete_selection_range(&mut content, &selection((0, 0), (0, 4)));
        type_at(&mut content, 0, 0, "テスト");
        assert_eq!(spans_of(&content, 0), pairs(&[("テスト", "")]));
        assert!(content.paragraphs()[0].children()[0].annotations_dropped);
    }

    #[test]
    fn text_typed_after_deleting_a_reading_drops_it() {
        let mut content = content(vec![Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            vec![reading("漢字", "かんじ")],
        )]);
        let cursor = delete_char_before(&mut content, &caret(0, 2)).unwrap();
        delete_char_before(&mut content, &cursor);
        assert_eq!(spans_of(&content, 0), pairs(&[("", "")]));
        type_at(&mut content, 0, 0, "テスト");
        assert_eq!(spans_of(&content, 0), pairs(&[("テスト", "")]));
    }

    fn reading_paragraph(spans: Vec<TextSpan>) -> TextContent {
        content(vec![Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.2,
            0.0,
            spans,
        )])
    }

    #[test]
    fn text_typed_after_a_reading_joins_the_next_plain_span() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ"), span("です")]);
        type_at(&mut content, 0, 2, "X");
        assert_eq!(
            spans_of(&content, 0),
            pairs(&[("漢字", "かんじ"), ("Xです", "")])
        );
    }

    #[test]
    fn text_typed_at_the_edges_of_a_reading_gets_its_own_span() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ")]);
        type_at(&mut content, 0, 2, "X");
        type_at(&mut content, 0, 0, "Y");
        assert_eq!(
            spans_of(&content, 0),
            pairs(&[("Y", ""), ("漢字", "かんじ"), ("X", "")])
        );
        let spans = content.paragraphs()[0].children();
        assert!(spans[0].annotations_dropped && spans[2].annotations_dropped);
        assert!(!spans[1].annotations_dropped);
    }

    #[test]
    fn text_typed_after_combined_digits_is_not_combined() {
        let mut digits = span("12");
        digits.text_combine_upright = crate::shapes::TextCombineUpright::All;
        let mut content = reading_paragraph(vec![span("第"), digits, span("回")]);
        type_at(&mut content, 0, 3, "月");
        let spans = content.paragraphs()[0].children();
        let texts: Vec<(&str, bool)> = spans
            .iter()
            .map(|span| {
                (
                    span.text.as_str(),
                    span.text_combine_upright == crate::shapes::TextCombineUpright::All,
                )
            })
            .collect();
        assert_eq!(texts, [("第", false), ("12", true), ("月回", false)]);
    }

    #[test]
    fn text_typed_between_two_readings_stays_out_of_both() {
        let mut content = reading_paragraph(vec![
            reading("漢字", "かんじ"),
            reading("東京", "とうきょう"),
        ]);
        type_at(&mut content, 0, 2, "X");
        assert_eq!(
            spans_of(&content, 0),
            pairs(&[("漢字", "かんじ"), ("X", ""), ("東京", "とうきょう")])
        );
    }

    #[test]
    fn text_typed_inside_a_reading_base_keeps_it() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ")]);
        type_at(&mut content, 0, 1, "X");
        assert_eq!(spans_of(&content, 0), pairs(&[("漢X字", "かんじ")]));

        // Between two spans of one reading the caret is inside its base.
        let joined = TextSpan {
            ruby_joins_previous: true,
            ..reading("字", "かんじ")
        };
        let mut content = reading_paragraph(vec![reading("漢", "かんじ"), joined]);
        type_at(&mut content, 0, 1, "X");
        assert_eq!(
            spans_of(&content, 0),
            pairs(&[("漢X", "かんじ"), ("字", "かんじ")])
        );
    }

    #[test]
    fn splitting_a_paragraph_inside_a_reading_base_drops_the_reading() {
        let mut content = reading_paragraph(vec![reading("日本", "にほん"), span("語")]);
        assert!(split_paragraph_at_cursor(&mut content, &caret(0, 1)));
        assert_eq!(spans_of(&content, 0), pairs(&[("日", "")]));
        assert_eq!(spans_of(&content, 1), pairs(&[("本", ""), ("語", "")]));
        assert!(content.paragraphs()[0].children()[0].annotations_dropped);
        assert!(content.paragraphs()[1].children()[0].annotations_dropped);

        // Between two spans of one reading the caret is inside its base.
        let joined = TextSpan {
            ruby_joins_previous: true,
            ..reading("本", "にほん")
        };
        let mut content = reading_paragraph(vec![reading("日", "にほん"), joined]);
        assert!(split_paragraph_at_cursor(&mut content, &caret(0, 1)));
        assert_eq!(spans_of(&content, 0), pairs(&[("日", "")]));
        assert_eq!(spans_of(&content, 1), pairs(&[("本", "")]));
    }

    #[test]
    fn splitting_a_paragraph_at_the_edges_of_a_reading_keeps_it() {
        for offset in [0, 2] {
            let mut content = reading_paragraph(vec![reading("日本", "にほん"), span("語")]);
            assert!(split_paragraph_at_cursor(&mut content, &caret(0, offset)));
            let spans: Vec<(String, String)> = content
                .paragraphs()
                .iter()
                .flat_map(|para| para.children().iter())
                .filter(|span| !span.text.is_empty())
                .map(|span| (span.text.clone(), span.ruby.clone()))
                .collect();
            assert_eq!(spans, pairs(&[("日本", "にほん"), ("語", "")]), "{offset}");
        }
    }

    #[test]
    fn text_typed_after_an_undrawn_annotation_or_warichu_stays_out() {
        for annotated in [
            TextSpan {
                hidden_annotation: true,
                ..span("漢字")
            },
            TextSpan {
                warichu: true,
                ..span("注記")
            },
        ] {
            let mut content = reading_paragraph(vec![annotated]);
            type_at(&mut content, 0, 2, "X");
            let spans = content.paragraphs()[0].children();
            assert_eq!(spans.len(), 2);
            assert!(spans[0].is_annotated());
            assert!(!spans[1].is_annotated() && spans[1].annotations_dropped);
            assert_eq!(spans[1].text, "X");
        }
    }

    #[test]
    fn overtyping_at_the_end_of_a_reading_replaces_the_next_text() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ"), span("です")]);
        replace_text_with_newlines(&mut content, &caret(0, 2), "X");
        assert_eq!(
            spans_of(&content, 0),
            pairs(&[("漢字", "かんじ"), ("Xす", "")])
        );
    }

    #[test]
    fn a_new_paragraph_never_starts_inside_a_reading() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ")]);
        assert!(split_paragraph_at_cursor(&mut content, &caret(0, 2)));
        assert_eq!(spans_of(&content, 1), pairs(&[("", "")]));

        let mut content = reading_paragraph(vec![reading("漢字", "かんじ")]);
        assert!(split_paragraph_at_cursor(&mut content, &caret(0, 0)));
        assert_eq!(spans_of(&content, 0), pairs(&[("", "")]));
        assert_eq!(spans_of(&content, 1), pairs(&[("漢字", "かんじ")]));
    }

    #[test]
    fn clearing_dropped_annotations_unmarks_every_span() {
        let mut content = reading_paragraph(vec![reading("漢字", "かんじ")]);
        type_at(&mut content, 0, 2, "X");
        content.clear_dropped_annotations();
        assert!(content.paragraphs()[0]
            .children()
            .iter()
            .all(|span| !span.annotations_dropped));
    }
}
