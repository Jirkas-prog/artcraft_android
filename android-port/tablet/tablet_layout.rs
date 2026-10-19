//! A bounded toolbar: controls keep their touch size and overflow scrolls.
use egui::{ScrollArea, Ui, vec2};

pub const TOUCH: f32 = 48.0;
pub const BAR: f32 = 56.0;

pub fn row<R>(ui: &mut Ui, id: &str, content: impl FnOnce(&mut Ui) -> R) -> R {
    ui.scope(|ui| {
        ui.spacing_mut().interact_size = vec2(TOUCH, TOUCH);
        ui.spacing_mut().item_spacing = vec2(4.0, 0.0);
        ui.style_mut().always_scroll_the_only_direction = true;
        ScrollArea::horizontal()
            .id_salt(id)
            .min_scrolled_width(0.0)
            .max_height(TOUCH)
            .auto_shrink([false, true])
            .show(ui, |ui| ui.horizontal(|ui| content(ui)).inner)
            .inner
    })
    .inner
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_and_zoom_fields_fit_the_toolbar_height() {
        let ctx = egui::Context::default();
        let mut value = "125%".to_owned();
        let mut output = ctx.run_ui(egui::RawInput::default(), |ui| {
            egui::Panel::bottom("navigation")
                .exact_size(BAR)
                .frame(egui::Frame::NONE.inner_margin(egui::Margin::symmetric(4, 4)))
                .show(ui, |ui| {
                    row(ui, "navigation-scroll", |ui| {
                        let r = ui.add(
                            egui::TextEdit::singleline(&mut value)
                                .desired_width(64.0)
                                .min_size(vec2(64.0, TOUCH)),
                        );
                        assert!(
                            ui.clip_rect().contains_rect(r.rect),
                            "field is clipped: {:?} in {:?}",
                            r.rect,
                            ui.clip_rect()
                        );
                    })
                });
        });
        output.textures_delta.clear();
    }

    #[test]
    fn controls_keep_their_size_and_scroll_into_a_narrow_viewport() {
        for width in [240.0, 360.0, 600.0, 1200.0] {
            let ctx = egui::Context::default();
            let mut last = egui::Rect::NOTHING;
            let mut clip = egui::Rect::NOTHING;
            for frame in 0..4 {
                let input = egui::RawInput {
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        vec2(width, 300.0),
                    )),
                    ..Default::default()
                };
                let mut output = ctx.run_ui(input, |ui| {
                    egui::Panel::top("tools").exact_size(BAR).show(ui, |ui| {
                        row(ui, "tools-scroll", |ui| {
                            clip = ui.clip_rect();
                            for index in 0..12 {
                                let r = ui.add_sized(
                                    vec2(TOUCH, TOUCH),
                                    egui::Button::new(index.to_string()),
                                );
                                assert!(r.rect.width() >= TOUCH && r.rect.height() >= TOUCH);
                                if index == 11 {
                                    last = r.rect;
                                    if frame == 1 {
                                        r.scroll_to_me_animation(
                                            Some(egui::Align::RIGHT),
                                            egui::style::ScrollAnimation::none(),
                                        );
                                    }
                                }
                            }
                        });
                    });
                    assert!(
                        ui.available_height() >= 230.0,
                        "toolbar consumed the document area"
                    );
                });
                output.textures_delta.clear();
            }
            assert!(
                clip.contains(last.center()),
                "last control inaccessible at width {width}: {last:?} in {clip:?}"
            );
            assert!(clip.width() <= width, "toolbar expanded past the viewport");
        }
    }
}
