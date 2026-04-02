use crate::shapes::Color;

pub trait UITheme {
    fn panel_background_color(&self) -> Color;
    fn panel_border_color(&self) -> Color;
}

#[allow(dead_code)]
#[derive(Default, Clone, Copy, PartialEq)]
pub struct LightTheme {}

impl UITheme for LightTheme {
    fn panel_background_color(&self) -> Color {
        Color::new(0xffffffff)
    }
    fn panel_border_color(&self) -> Color {
        Color::new(0xffeef0f2)
    }
}

#[derive(Default, Clone, Copy, PartialEq)]
pub struct DarkTheme {}

impl UITheme for DarkTheme {
    fn panel_background_color(&self) -> Color {
        Color::new(0xff18181a)
    }

    fn panel_border_color(&self) -> Color {
        Color::new(0xff2e3434)
    }
}