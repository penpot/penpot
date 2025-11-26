pub mod events;

use skia_safe::{Canvas, Font, Paint, Point, Color};

pub struct TextEditor {
    text: Vec<String>,
    font: Font,
    cursor_pos: Point,
}

impl TextEditor {
    pub fn new(font: Font) -> Self {
        TextEditor {
            text: vec!["Hello, Skia!".to_string()],
            font,
            cursor_pos: Point::new(0.0, 0.0),
        }
    }

    pub fn render(&self, canvas: &Canvas) {
        let mut paint = Paint::default();
        paint.set_color(Color::BLACK);
        paint.set_anti_alias(true);

        for (i, line) in self.text.iter().enumerate() {
            canvas.draw_str(line, (20.0, 20.0 + (i as f32 * 18.0)), &self.font, &paint);
        }

        // Draw cursor - with bounds checking
        let mut cursor_paint = Paint::default();
        cursor_paint.set_color(Color::BLACK);
        cursor_paint.set_anti_alias(true);
        
        let y_idx = self.cursor_pos.y as usize;
        let x_idx = self.cursor_pos.x as usize;
        
        if y_idx < self.text.len() {
            let line = &self.text[y_idx];
            let safe_x_idx = x_idx.min(line.len());
            
            let (x, _) = if safe_x_idx > 0 {
                self.font.measure_str(&line[..safe_x_idx], None)
            } else {
                (0.0, skia_safe::Rect::new_empty())
            };
            
            let cursor_rect = skia_safe::Rect::from_xywh(
                20.0 + x, 
                20.0 + (y_idx as f32 * 18.0) - 18.0, 
                1.0, 
                18.0
            );
            canvas.draw_rect(cursor_rect, &cursor_paint);
        }
    }

    pub fn handle_keydown(&mut self, key: &str) {
        if self.text.is_empty() {
            self.text.push(String::new());
        }
        
        let y = self.cursor_pos.y as usize;
        let x = self.cursor_pos.x as usize;
        
        if y >= self.text.len() {
            return;
        }
        
        match key {
            "ArrowLeft" => {
                if x > 0 {
                    self.cursor_pos.x -= 1.0;
                } else if y > 0 {
                    self.cursor_pos.y -= 1.0;
                    self.cursor_pos.x = self.text[y - 1].len() as f32;
                }
            }
            "ArrowRight" => {
                if x < self.text[y].len() {
                    self.cursor_pos.x += 1.0;
                } else if y < self.text.len() - 1 {
                    self.cursor_pos.y += 1.0;
                    self.cursor_pos.x = 0.0;
                }
            }
            "ArrowUp" => {
                if y > 0 {
                    self.cursor_pos.y -= 1.0;
                    let new_y = y - 1;
                    self.cursor_pos.x = self.cursor_pos.x.min(self.text[new_y].len() as f32);
                }
            }
            "ArrowDown" => {
                if y < self.text.len() - 1 {
                    self.cursor_pos.y += 1.0;
                    let new_y = y + 1;
                    self.cursor_pos.x = self.cursor_pos.x.min(self.text[new_y].len() as f32);
                }
            }
            "Backspace" => {
                if x > 0 {
                    self.text[y].remove(x - 1);
                    self.cursor_pos.x -= 1.0;
                } else if y > 0 {
                    let line = self.text.remove(y);
                    self.cursor_pos.y -= 1.0;
                    self.cursor_pos.x = self.text[y - 1].len() as f32;
                    self.text[y - 1].push_str(&line);
                }
            }
            "Enter" => {
                let line = self.text[y].split_off(x);
                self.text.insert(y + 1, line);
                self.cursor_pos.y += 1.0;
                self.cursor_pos.x = 0.0;
            }
            _ => {
                if key.len() == 1 {
                    if let Some(ch) = key.chars().next() {
                        self.text[y].insert(x, ch);
                        self.cursor_pos.x += 1.0;
                    }
                }
            }
        }
    }

    pub fn handle_mousedown(&mut self, x: f32, y: f32) {
        println!("@@@ Mouse down at: ({}, {})", x, y);
        if self.text.is_empty() {
            self.text.push(String::new());
        }
        
        let line_height = 18.0;
        let y_pos = ((y - 20.0) / line_height).floor().max(0.0) as usize;
        let y_pos = y_pos.min(self.text.len() - 1);
        self.cursor_pos.y = y_pos as f32;

        let line = &self.text[y_pos];
        let mut closest_pos = 0;
        let mut min_dist = f32::MAX;
        
        for i in 0..=line.len() {
            let (width, _) = self.font.measure_str(&line[..i], None);
            let dist = (x - (20.0 + width)).abs();
            if dist < min_dist {
                min_dist = dist;
                closest_pos = i;
            }
        }
        self.cursor_pos.x = closest_pos as f32;
    }
}
