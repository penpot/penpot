#![allow(dead_code)]

use crate::shapes::{TextContent, TextPositionWithAffinity};
use crate::uuid::Uuid;
use crate::wasm::text::helpers as text_helpers;
use skia_safe::{
    textlayout::{Affinity, PositionWithAffinity},
    Color,
};

#[derive(Debug, Clone, Copy, Default)]
pub struct TextSelection {
    pub anchor: TextPositionWithAffinity,
    pub focus: TextPositionWithAffinity,
}

impl TextSelection {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_position_with_affinity(position: TextPositionWithAffinity) -> Self {
        Self {
            anchor: position,
            focus: position,
        }
    }

    pub fn is_collapsed(&self) -> bool {
        self.anchor == self.focus
    }

    pub fn is_selection(&self) -> bool {
        !self.is_collapsed()
    }

    pub fn reset(&mut self) {
        self.anchor.reset();
        self.focus.reset();
    }

    pub fn set_caret(&mut self, cursor: TextPositionWithAffinity) {
        self.anchor = cursor;
        self.focus = cursor;
    }

    pub fn extend_to(&mut self, cursor: TextPositionWithAffinity) {
        self.focus = cursor;
    }

    pub fn collapse_to_focus(&mut self) {
        self.anchor = self.focus;
    }

    pub fn collapse_to_anchor(&mut self) {
        self.focus = self.anchor;
    }

    pub fn start(&self) -> TextPositionWithAffinity {
        if self.anchor.paragraph < self.focus.paragraph {
            self.anchor
        } else if self.anchor.paragraph > self.focus.paragraph {
            self.focus
        } else if self.anchor.offset <= self.focus.offset {
            self.anchor
        } else {
            self.focus
        }
    }

    pub fn end(&self) -> TextPositionWithAffinity {
        if self.anchor.paragraph > self.focus.paragraph {
            self.anchor
        } else if self.anchor.paragraph < self.focus.paragraph {
            self.focus
        } else if self.anchor.offset >= self.focus.offset {
            self.anchor
        } else {
            self.focus
        }
    }
}

/// Events that the text editor can emit for frontend synchronization
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum TextEditorEvent {
    None = 0,
    ContentChanged = 1,
    SelectionChanged = 2,
    NeedsLayout = 3,
}

/// FIXME: It should be better to get these constants from the frontend through the API.
const SELECTION_COLOR: Color = Color::from_argb(127, 0, 209, 184);
const CURSOR_WIDTH: f32 = 1.5;
const CURSOR_COLOR: Color = Color::BLACK;
const CURSOR_BLINK_INTERVAL_MS: f64 = 530.0;

pub struct TextEditorTheme {
    pub selection_color: Color,
    pub cursor_width: f32,
    pub cursor_color: Color,
}

pub struct TextEditorState {
    pub theme: TextEditorTheme,
    pub selection: TextSelection,
    pub has_focus: bool,
    // This property indicates that we've started
    // selecting something with the pointer.
    pub is_pointer_selection_active: bool,
    pub active_shape_id: Option<Uuid>,
    pub cursor_visible: bool,
    pub last_blink_time: f64,
    pending_events: Vec<TextEditorEvent>,
}

impl TextEditorState {
    pub fn new() -> Self {
        Self {
            theme: TextEditorTheme {
                selection_color: SELECTION_COLOR,
                cursor_width: CURSOR_WIDTH,
                cursor_color: CURSOR_COLOR,
            },
            selection: TextSelection::new(),
            has_focus: false,
            is_pointer_selection_active: false,
            active_shape_id: None,
            cursor_visible: true,
            last_blink_time: 0.0,
            pending_events: Vec::new(),
        }
    }

    pub fn focus(&mut self, shape_id: Uuid) {
        self.has_focus = true;
        self.active_shape_id = Some(shape_id);
        self.cursor_visible = true;
        self.last_blink_time = 0.0;
        self.selection.reset();
        self.is_pointer_selection_active = false;
        self.pending_events.clear();
    }

    pub fn blur(&mut self) {
        self.has_focus = false;
        // self.active_shape_id = None;
        self.cursor_visible = false;
        self.last_blink_time = 0.0;
        // self.selection.reset();
        self.is_pointer_selection_active = false;
        self.pending_events.clear();
    }

    pub fn dispose(&mut self) {
        self.has_focus = false;
        self.active_shape_id = None;
        self.cursor_visible = false;
        self.last_blink_time = 0.0;
        self.selection.reset();
        self.is_pointer_selection_active = false;
        self.pending_events.clear();
    }

    pub fn start_pointer_selection(&mut self) -> bool {
        if self.is_pointer_selection_active {
            return false;
        }
        self.is_pointer_selection_active = true;
        true
    }

    pub fn stop_pointer_selection(&mut self) -> bool {
        if !self.is_pointer_selection_active {
            return false;
        }
        self.is_pointer_selection_active = false;
        true
    }

    pub fn select_all(&mut self, content: &TextContent) -> bool {
        self.is_pointer_selection_active = false;
        self.set_caret_from_position(&TextPositionWithAffinity::empty());
        let num_paragraphs = content.paragraphs().len() - 1;
        let Some(last_paragraph) = content.paragraphs().last() else {
            return false;
        };
        #[allow(dead_code)]
        let _num_spans = last_paragraph.children().len() - 1;
        let Some(_last_text_span) = last_paragraph.children().last() else {
            return false;
        };
        let mut offset = 0;
        for span in last_paragraph.children() {
            offset += span.text.len();
        }
        self.extend_selection_from_position(&TextPositionWithAffinity::new(
            PositionWithAffinity {
                position: offset as i32,
                affinity: Affinity::Upstream,
            },
            num_paragraphs,
            offset,
        ));
        self.reset_blink();
        self.push_event(crate::state::TextEditorEvent::SelectionChanged);

        true
    }

    pub fn select_word_boundary(
        &mut self,
        content: &TextContent,
        position: &TextPositionWithAffinity,
    ) {
        self.is_pointer_selection_active = false;

        let paragraphs = content.paragraphs();
        if paragraphs.is_empty() || position.paragraph >= paragraphs.len() {
            return;
        }

        let paragraph = &paragraphs[position.paragraph];
        let paragraph_text: String = paragraph
            .children()
            .iter()
            .map(|span| span.text.as_str())
            .collect();

        let chars: Vec<char> = paragraph_text.chars().collect();
        if chars.is_empty() {
            self.set_caret_from_position(&TextPositionWithAffinity::new_without_affinity(
                position.paragraph,
                0,
            ));
            self.reset_blink();
            self.push_event(TextEditorEvent::SelectionChanged);
            return;
        }

        let mut offset = position.offset.min(chars.len());

        if offset == chars.len() {
            offset = offset.saturating_sub(1);
        } else if !text_helpers::is_word_char(chars[offset])
            && offset > 0
            && text_helpers::is_word_char(chars[offset - 1])
        {
            offset -= 1;
        }

        if !text_helpers::is_word_char(chars[offset]) {
            self.set_caret_from_position(&TextPositionWithAffinity::new_without_affinity(
                position.paragraph,
                position.offset.min(chars.len()),
            ));
            self.reset_blink();
            self.push_event(TextEditorEvent::SelectionChanged);
            return;
        }

        let mut start = offset;
        while start > 0 && text_helpers::is_word_char(chars[start - 1]) {
            start -= 1;
        }

        let mut end = offset + 1;
        while end < chars.len() && text_helpers::is_word_char(chars[end]) {
            end += 1;
        }

        self.set_caret_from_position(&TextPositionWithAffinity::new_without_affinity(
            position.paragraph,
            start,
        ));
        self.extend_selection_from_position(&TextPositionWithAffinity::new_without_affinity(
            position.paragraph,
            end,
        ));
        self.reset_blink();
        self.push_event(TextEditorEvent::SelectionChanged);
    }

    pub fn set_caret_from_position(&mut self, position: &TextPositionWithAffinity) {
        self.selection.set_caret(*position);
        self.push_event(TextEditorEvent::SelectionChanged);
    }

    pub fn extend_selection_from_position(&mut self, position: &TextPositionWithAffinity) {
        self.selection.extend_to(*position);
        self.push_event(TextEditorEvent::SelectionChanged);
    }

    pub fn update_blink(&mut self, timestamp_ms: f64) {
        if !self.has_focus {
            return;
        }

        if self.last_blink_time == 0.0 {
            self.last_blink_time = timestamp_ms;
            self.cursor_visible = true;
            return;
        }

        let elapsed = timestamp_ms - self.last_blink_time;
        if elapsed >= CURSOR_BLINK_INTERVAL_MS {
            self.cursor_visible = !self.cursor_visible;
            self.last_blink_time = timestamp_ms;
        }
    }

    pub fn reset_blink(&mut self) {
        self.cursor_visible = true;
        self.last_blink_time = 0.0;
    }

    pub fn push_event(&mut self, event: TextEditorEvent) {
        if self.pending_events.last() != Some(&event) {
            self.pending_events.push(event);
        }
    }

    pub fn poll_event(&mut self) -> TextEditorEvent {
        self.pending_events.pop().unwrap_or(TextEditorEvent::None)
    }

    pub fn has_pending_events(&self) -> bool {
        !self.pending_events.is_empty()
    }
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}
