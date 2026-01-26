#![allow(dead_code)]

use crate::shapes::TextPositionWithAffinity;
use crate::uuid::Uuid;

/// Cursor position within text content.
/// Uses character offsets for precise positioning.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Default)]
pub struct TextCursor {
    pub paragraph: usize,
    pub char_offset: usize,
}

impl TextCursor {
    pub fn new(paragraph: usize, char_offset: usize) -> Self {
        Self {
            paragraph,
            char_offset,
        }
    }

    pub fn zero() -> Self {
        Self {
            paragraph: 0,
            char_offset: 0,
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct TextSelection {
    pub anchor: TextCursor,
    pub focus: TextCursor,
}

impl TextSelection {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_cursor(cursor: TextCursor) -> Self {
        Self {
            anchor: cursor,
            focus: cursor,
        }
    }

    pub fn is_collapsed(&self) -> bool {
        self.anchor == self.focus
    }

    pub fn is_selection(&self) -> bool {
        !self.is_collapsed()
    }

    pub fn set_caret(&mut self, cursor: TextCursor) {
        self.anchor = cursor;
        self.focus = cursor;
    }

    pub fn extend_to(&mut self, cursor: TextCursor) {
        self.focus = cursor;
    }

    pub fn collapse_to_focus(&mut self) {
        self.anchor = self.focus;
    }

    pub fn collapse_to_anchor(&mut self) {
        self.focus = self.anchor;
    }

    pub fn start(&self) -> TextCursor {
        if self.anchor.paragraph < self.focus.paragraph {
            self.anchor
        } else if self.anchor.paragraph > self.focus.paragraph {
            self.focus
        } else if self.anchor.char_offset <= self.focus.char_offset {
            self.anchor
        } else {
            self.focus
        }
    }

    pub fn end(&self) -> TextCursor {
        if self.anchor.paragraph > self.focus.paragraph {
            self.anchor
        } else if self.anchor.paragraph < self.focus.paragraph {
            self.focus
        } else if self.anchor.char_offset >= self.focus.char_offset {
            self.anchor
        } else {
            self.focus
        }
    }
}

/// Events that the text editor can emit for frontend synchronization
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum EditorEvent {
    None = 0,
    ContentChanged = 1,
    SelectionChanged = 2,
    NeedsLayout = 3,
}

pub struct TextEditorState {
    pub selection: TextSelection,
    pub is_active: bool,
    pub active_shape_id: Option<Uuid>,
    pub cursor_visible: bool,
    pub last_blink_time: f64,
    pub x_affinity: Option<f32>,
    pending_events: Vec<EditorEvent>,
}

const CURSOR_BLINK_INTERVAL_MS: f64 = 530.0;

impl TextEditorState {
    pub fn new() -> Self {
        Self {
            selection: TextSelection::new(),
            is_active: false,
            active_shape_id: None,
            cursor_visible: true,
            last_blink_time: 0.0,
            x_affinity: None,
            pending_events: Vec::new(),
        }
    }

    pub fn start(&mut self, shape_id: Uuid) {
        self.is_active = true;
        self.active_shape_id = Some(shape_id);
        self.cursor_visible = true;
        self.last_blink_time = 0.0;
        self.selection = TextSelection::new();
        self.x_affinity = None;
        self.pending_events.clear();
    }

    pub fn stop(&mut self) {
        self.is_active = false;
        self.active_shape_id = None;
        self.cursor_visible = false;
        self.x_affinity = None;
        self.pending_events.clear();
    }

    pub fn set_caret_from_position(&mut self, position: TextPositionWithAffinity) {
        let cursor = TextCursor::new(position.paragraph as usize, position.offset as usize);
        self.selection.set_caret(cursor);
        self.reset_blink();
        self.clear_x_affinity();
        self.push_event(EditorEvent::SelectionChanged);
    }

    pub fn extend_selection_from_position(&mut self, position: TextPositionWithAffinity) {
        let cursor = TextCursor::new(position.paragraph as usize, position.offset as usize);
        self.selection.extend_to(cursor);
        self.reset_blink();
        self.push_event(EditorEvent::SelectionChanged);
    }

    pub fn update_blink(&mut self, timestamp_ms: f64) {
        if !self.is_active {
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

    pub fn clear_x_affinity(&mut self) {
        self.x_affinity = None;
    }

    pub fn push_event(&mut self, event: EditorEvent) {
        if self.pending_events.last() != Some(&event) {
            self.pending_events.push(event);
        }
    }

    pub fn poll_event(&mut self) -> EditorEvent {
        self.pending_events.pop().unwrap_or(EditorEvent::None)
    }

    pub fn has_pending_events(&self) -> bool {
        !self.pending_events.is_empty()
    }

    pub fn set_caret_position_from(
        &mut self,
        text_position_with_affinity: TextPositionWithAffinity,
    ) {
        self.set_caret_from_position(text_position_with_affinity);
    }
}

/// TODO: Remove legacy code
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct TextNodePosition {
    pub paragraph: i32,
    pub span: i32,
}

impl TextNodePosition {
    pub fn new(paragraph: i32, span: i32) -> Self {
        Self { paragraph, span }
    }

    pub fn is_invalid(&self) -> bool {
        self.paragraph < 0 || self.span < 0
    }
}
