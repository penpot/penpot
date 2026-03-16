use crate::shapes::{Paragraph, TextContent, TextDirection, TextPositionWithAffinity};
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
        return TextPositionWithAffinity::new_without_affinity(0, 0);
    }

    let para_idx = position.paragraph.min(paragraphs.len() - 1);
    let para_len = paragraph_char_count(&paragraphs[para_idx]);
    let char_offset = position.offset.min(para_len);

    TextPositionWithAffinity::new_without_affinity(para_idx, char_offset)
}

/// Move cursor left by one character.
pub fn move_cursor_backward(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    word_boundary: bool,
) -> TextPositionWithAffinity {
    if !word_boundary {
        if cursor.offset > 0 {
            return TextPositionWithAffinity::new_without_affinity(
                cursor.paragraph,
                cursor.offset - 1,
            );
        }
        if cursor.paragraph > 0 {
            let prev_para = cursor.paragraph - 1;
            let char_count = paragraph_char_count(&paragraphs[prev_para]);
            return TextPositionWithAffinity::new_without_affinity(prev_para, char_count);
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

    TextPositionWithAffinity::new_without_affinity(para_idx, offset)
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
            return TextPositionWithAffinity::new_without_affinity(
                cursor.paragraph,
                cursor.offset + 1,
            );
        }
        if cursor.paragraph < paragraphs.len() - 1 {
            return TextPositionWithAffinity::new_without_affinity(cursor.paragraph + 1, 0);
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

    TextPositionWithAffinity::new_without_affinity(para_idx, offset)
}

/// Move cursor up by one line.
pub fn move_cursor_up(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
) -> TextPositionWithAffinity {
    // TODO: Implement proper line-based navigation using line metrics
    if cursor.paragraph > 0 {
        let prev_para = cursor.paragraph - 1;
        let char_count = paragraph_char_count(&paragraphs[prev_para]);
        let new_offset = cursor.offset.min(char_count);
        TextPositionWithAffinity::new_without_affinity(prev_para, new_offset)
    } else {
        TextPositionWithAffinity::new_without_affinity(cursor.paragraph, 0)
    }
}

/// Move cursor down by one line.
pub fn move_cursor_down(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
    _text_content: &TextContent,
) -> TextPositionWithAffinity {
    // TODO: Implement proper line-based navigation using line metrics
    if cursor.paragraph < paragraphs.len() - 1 {
        let next_para = cursor.paragraph + 1;
        let char_count = paragraph_char_count(&paragraphs[next_para]);
        let new_offset = cursor.offset.min(char_count);
        TextPositionWithAffinity::new_without_affinity(next_para, new_offset)
    } else {
        let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
        TextPositionWithAffinity::new_without_affinity(cursor.paragraph, char_count)
    }
}

/// Move cursor to start of current line.
pub fn move_cursor_line_start(
    cursor: &TextPositionWithAffinity,
    _paragraphs: &[Paragraph],
) -> TextPositionWithAffinity {
    // TODO: Implement proper line-start using line metrics
    TextPositionWithAffinity::new_without_affinity(cursor.paragraph, 0)
}

/// Move cursor to end of current line.
pub fn move_cursor_line_end(
    cursor: &TextPositionWithAffinity,
    paragraphs: &[Paragraph],
) -> TextPositionWithAffinity {
    // TODO: Implement proper line-end using line metrics
    let char_count = paragraph_char_count(&paragraphs[cursor.paragraph]);
    TextPositionWithAffinity::new_without_affinity(cursor.paragraph, char_count)
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

    if let Some(new_offset) = insert_text_at_cursor(text_content, &current_cursor, lines[0]) {
        current_cursor =
            TextPositionWithAffinity::new_without_affinity(current_cursor.paragraph, new_offset);
    } else {
        return None;
    }

    for line in lines.iter().skip(1) {
        if !split_paragraph_at_cursor(text_content, &current_cursor) {
            break;
        }
        current_cursor =
            TextPositionWithAffinity::new_without_affinity(current_cursor.paragraph + 1, 0);
        if let Some(new_offset) = insert_text_at_cursor(text_content, &current_cursor, line) {
            current_cursor = TextPositionWithAffinity::new_without_affinity(
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
        children[0].set_text(text.to_string());
        return Some(text.chars().count());
    }

    let (span_idx, offset_in_span) = find_text_span_at_offset(para, cursor.offset)?;

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

/// Delete a range of text specified by a selection.
pub fn delete_selection_range(text_content: &mut TextContent, selection: &TextSelection) {
    let start = selection.start();
    let end = selection.end();

    let paragraphs = text_content.paragraphs_mut();
    if start.paragraph >= paragraphs.len() {
        return;
    }

    if start.paragraph == end.paragraph {
        delete_range_in_paragraph(&mut paragraphs[start.paragraph], start.offset, end.offset);
    } else {
        let start_para_len = paragraph_char_count(&paragraphs[start.paragraph]);
        delete_range_in_paragraph(
            &mut paragraphs[start.paragraph],
            start.offset,
            start_para_len,
        );

        delete_range_in_paragraph(&mut paragraphs[end.paragraph], 0, end.offset);

        if end.paragraph < paragraphs.len() {
            let end_para_children: Vec<_> =
                paragraphs[end.paragraph].children_mut().drain(..).collect();
            paragraphs[start.paragraph]
                .children_mut()
                .extend(end_para_children);
        }

        if end.paragraph < paragraphs.len() {
            paragraphs.drain((start.paragraph + 1)..=end.paragraph);
        }

        let children = paragraphs[start.paragraph].children_mut();
        let has_content = children.iter().any(|span| !span.text.is_empty());
        if has_content {
            children.retain(|span| !span.text.is_empty());
        } else if children.len() > 1 {
            children.truncate(1);
        }
    }
}

/// Delete a range of characters within a single paragraph.
pub fn delete_range_in_paragraph(para: &mut Paragraph, start_offset: usize, end_offset: usize) {
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

    let has_content = children.iter().any(|span| !span.text.is_empty());
    if has_content {
        children.retain(|span| !span.text.is_empty());
    } else if !children.is_empty() {
        children.truncate(1);
    }
}

/// Delete the character before the cursor. Returns the new cursor position.
pub fn delete_char_before(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
) -> Option<TextPositionWithAffinity> {
    if cursor.offset > 0 {
        let paragraphs = text_content.paragraphs_mut();
        let para = &mut paragraphs[cursor.paragraph];
        let delete_pos = cursor.offset - 1;
        delete_range_in_paragraph(para, delete_pos, cursor.offset);
        Some(TextPositionWithAffinity::new_without_affinity(
            cursor.paragraph,
            delete_pos,
        ))
    } else if cursor.paragraph > 0 {
        let prev_para_idx = cursor.paragraph - 1;
        let paragraphs = text_content.paragraphs_mut();
        let prev_para_len = paragraph_char_count(&paragraphs[prev_para_idx]);

        let current_children: Vec<_> = paragraphs[cursor.paragraph]
            .children_mut()
            .drain(..)
            .collect();
        paragraphs[prev_para_idx]
            .children_mut()
            .extend(current_children);

        paragraphs.remove(cursor.paragraph);

        Some(TextPositionWithAffinity::new_without_affinity(
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
        anchor: TextPositionWithAffinity::new_without_affinity(start_paragraph, start_offset),
        focus: TextPositionWithAffinity::new_without_affinity(end_paragraph, end_offset),
    };

    delete_selection_range(text_content, &selection);

    Some(TextPositionWithAffinity::new_without_affinity(
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
        anchor: TextPositionWithAffinity::new_without_affinity(start_paragraph, start_offset),
        focus: TextPositionWithAffinity::new_without_affinity(end_paragraph, end_offset),
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
        delete_range_in_paragraph(para, cursor.offset, cursor.offset + 1);
    } else if cursor.paragraph < paragraphs.len() - 1 {
        let next_para_idx = cursor.paragraph + 1;
        let next_children: Vec<_> = paragraphs[next_para_idx].children_mut().drain(..).collect();
        paragraphs[cursor.paragraph]
            .children_mut()
            .extend(next_children);

        paragraphs.remove(next_para_idx);
    }
}

/// Split a paragraph at the cursor position. Returns true if split was successful.
pub fn split_paragraph_at_cursor(
    text_content: &mut TextContent,
    cursor: &TextPositionWithAffinity,
) -> bool {
    let paragraphs = text_content.paragraphs_mut();
    if cursor.paragraph >= paragraphs.len() {
        return false;
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
        let mut empty_span = current_span.clone();
        empty_span.set_text(String::new());
        new_para_children.push(empty_span);
    }

    let text_align = para.text_align();
    let text_direction = para.text_direction();
    let text_decoration = para.text_decoration();
    let text_transform = para.text_transform();
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

    let new_para = crate::shapes::Paragraph::new(
        text_align,
        text_direction,
        text_decoration,
        text_transform,
        line_height,
        letter_spacing,
        new_para_children,
    );

    paragraphs.insert(cursor.paragraph + 1, new_para);

    true
}
