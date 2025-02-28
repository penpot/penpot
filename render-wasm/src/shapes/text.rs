#[derive(Debug, PartialEq, Clone)]
pub struct TextContent {
    paragraphs: Vec<Paragraph>,
    width: f32,
    height: f32,
    x: f32,
    y: f32,
}

impl TextContent {
    pub fn set_xywh(&mut self, x: f32, y: f32, w: f32, h: f32) {
        self.x = x;
        self.y = y;
        self.width = w;
        self.height = h;
    }

    pub fn add_paragraph(&mut self) {
        let p = Paragraph::default();
        self.paragraphs.push(p);
    }

    pub fn add_leaf(&mut self, text: &str) -> Result<(), String> {
        let paragraph = self
            .paragraphs
            .last_mut()
            .ok_or("No paragraph to add text leaf to")?;

        paragraph.add_leaf(TextLeaf {
            text: text.to_owned(),
        });

        Ok(())
    }
}

impl Default for TextContent {
    fn default() -> Self {
        Self {
            paragraphs: vec![],
            width: 0.,
            height: 0.,
            x: 0.,
            y: 0.,
        }
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct Paragraph {
    children: Vec<TextLeaf>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self { children: vec![] }
    }
}

impl Paragraph {
    fn add_leaf(&mut self, leaf: TextLeaf) {
        self.children.push(leaf);
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextLeaf {
    text: String,
}
