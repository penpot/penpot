#![allow(dead_code)]

use crate::shapes::TextPositionWithAffinity;

/// TODO: Now this is just a tuple with 2 i32 working
/// as indices (paragraph and span).
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct TextNodePosition {
    pub paragraph: i32,
    pub span: i32,
}

impl TextNodePosition {
    pub fn new(paragraph: i32, span: i32) -> Self {
        Self { paragraph, span }
    }

    #[allow(dead_code)]
    pub fn is_invalid(&self) -> bool {
        self.paragraph < 0 || self.span < 0
    }
}

pub struct TextPosition {
    node: Option<TextNodePosition>,
    offset: i32,
}

impl TextPosition {
    pub fn new() -> Self {
        Self {
            node: None,
            offset: -1,
        }
    }

    pub fn set(&mut self, node: Option<TextNodePosition>, offset: i32) {
        self.node = node;
        self.offset = offset;
    }
}

pub struct TextSelection {
    focus: TextPosition,
    anchor: TextPosition,
}

impl TextSelection {
    pub fn new() -> Self {
        Self {
            focus: TextPosition::new(),
            anchor: TextPosition::new(),
        }
    }

    #[allow(dead_code)]
    pub fn is_caret(&self) -> bool {
        self.focus.node == self.anchor.node && self.focus.offset == self.anchor.offset
    }

    #[allow(dead_code)]
    pub fn is_selection(&self) -> bool {
        !self.is_caret()
    }

    pub fn set_focus(&mut self, node: Option<TextNodePosition>, offset: i32) {
        self.focus.set(node, offset);
    }

    pub fn set_anchor(&mut self, node: Option<TextNodePosition>, offset: i32) {
        self.anchor.set(node, offset);
    }

    pub fn set(&mut self, node: Option<TextNodePosition>, offset: i32) {
        self.set_focus(node, offset);
        self.set_anchor(node, offset);
    }
}

pub struct TextEditorState {
    selection: TextSelection,
}

impl TextEditorState {
    pub fn new() -> Self {
        Self {
            selection: TextSelection::new(),
        }
    }

    pub fn set_caret_position_from(
        &mut self,
        text_position_with_affinity: TextPositionWithAffinity,
    ) {
        self.selection.set(
            Some(TextNodePosition::new(
                text_position_with_affinity.paragraph,
                text_position_with_affinity.span,
            )),
            text_position_with_affinity.offset,
        );
    }
}
