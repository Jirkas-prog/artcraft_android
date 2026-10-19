use craft_tablet_layout::{BAR, TOUCH, row};
use egui::{Context, Event, Pos2, RawInput, Rect, TouchDeviceId, TouchId, TouchPhase, vec2};

fn frame(ctx: &Context, time: f64, phase: Option<TouchPhase>, x: f32) -> (Rect, bool) {
    let mut input = RawInput {
        time: Some(time),
        screen_rect: Some(Rect::from_min_size(Pos2::ZERO, vec2(360.0, 300.0))),
        ..Default::default()
    };
    if let Some(phase) = phase {
        input.events.push(Event::Touch {
            device_id: TouchDeviceId(0),
            id: TouchId(1),
            phase,
            pos: Pos2::new(x, 28.0),
            force: None,
        });
    }
    craft_android_gestures::filter(ctx, &mut input);
    let mut first = Rect::NOTHING;
    let mut clicked = false;
    let mut output = ctx.run_ui(input, |ui| {
        egui::Panel::top("toolbar").exact_size(BAR).show(ui, |ui| {
            row(ui, "tools", |ui| {
                for index in 0..12 {
                    let r = ui.add_sized(vec2(TOUCH, TOUCH), egui::Button::new(index.to_string()));
                    clicked |= r.clicked();
                    if index == 0 {
                        first = r.rect;
                    }
                }
            });
        });
    });
    output.textures_delta.clear();
    (first, clicked)
}

#[test]
fn a_finger_swipe_scrolls_toolbar_without_running_a_command() {
    let ctx = Context::default();
    let (before, _) = frame(&ctx, 0.0, None, 0.0);
    for (time, phase, x) in [
        (0.1, TouchPhase::Start, 200.0),
        (0.2, TouchPhase::Move, 120.0),
        (0.3, TouchPhase::Move, 50.0),
        (0.4, TouchPhase::End, 50.0),
    ] {
        assert!(!frame(&ctx, time, Some(phase), x).1);
    }
    let (after, clicked) = frame(&ctx, 0.5, None, 0.0);
    assert!(!clicked);
    assert!(
        after.left() < before.left() - 100.0,
        "toolbar did not follow the finger: {before:?} -> {after:?}"
    );
}

#[test]
fn a_short_tap_still_activates_a_toolbar_button() {
    let ctx = Context::default();
    let (first, _) = frame(&ctx, 0.0, None, 0.0);
    assert!(!frame(&ctx, 0.1, Some(TouchPhase::Start), first.center().x).1);
    assert!(frame(&ctx, 0.2, Some(TouchPhase::End), first.center().x).1);
}
