//! Android fingers navigate; a deliberate hold starts the active tool's drag.
//! The Android winit patch reserves `force: Some` for a stylus and does not make
//! mouse events for fingers. Pen pressure and real mouse events pass unchanged.

use egui::{
    Context, Event, Id, Modifiers, MouseWheelUnit, Pos2, RawInput, TouchDeviceId, TouchId,
    TouchPhase, Vec2,
};
use std::time::Duration;

const HOLD: f64 = 0.5;
const SLOP: f32 = 8.0;
const NAVIGATION: &str = "craft.android.finger-navigation";

#[derive(Clone, Copy, Default, PartialEq)]
enum Mode {
    #[default]
    Idle,
    Pending,
    Drag,
    Navigate,
    Suppressed,
}

#[derive(Clone)]
struct Contact {
    device: TouchDeviceId,
    id: TouchId,
    pos: Pos2,
}

#[derive(Clone, Default)]
struct Fingers {
    contacts: Vec<Contact>,
    mode: Mode,
    origin: Pos2,
    started: f64,
    pen_down: bool,
    gone_next_frame: bool,
}

#[derive(Default)]
struct Navigation {
    anchor: Option<Pos2>,
    delta: Vec2,
    zoom: Option<f32>,
    started: bool,
    ended: bool,
}

fn button(events: &mut Vec<Event>, pos: Pos2, pressed: bool, modifiers: Modifiers) {
    events.push(Event::PointerButton {
        pos,
        button: egui::PointerButton::Primary,
        pressed,
        modifiers,
    });
}

impl Fingers {
    fn geometry(&self) -> Option<(Pos2, f32)> {
        let first = self.contacts.first()?;
        if let Some(second) = self.contacts.get(1) {
            Some((
                first.pos + (second.pos - first.pos) * 0.5,
                first.pos.distance(second.pos),
            ))
        } else {
            Some((first.pos, 0.0))
        }
    }

    fn promote_hold(&mut self, now: f64, events: &mut Vec<Event>, modifiers: Modifiers) {
        if self.mode == Mode::Pending && now - self.started >= HOLD {
            self.mode = Mode::Drag;
            events.push(Event::PointerMoved(self.origin));
            button(events, self.origin, true, modifiers);
        }
    }

    fn stop(&mut self, events: &mut Vec<Event>, modifiers: Modifiers) {
        if self.mode == Mode::Drag {
            button(
                events,
                self.geometry().map_or(self.origin, |g| g.0),
                false,
                modifiers,
            );
        }
        if !self.pen_down {
            events.push(Event::PointerGone);
        }
        self.contacts.clear();
        self.mode = Mode::Idle;
        self.gone_next_frame = false;
    }

    fn navigate(&mut self, nav: &mut Navigation, events: &mut Vec<Event>, modifiers: Modifiers) {
        if self.mode == Mode::Drag {
            button(
                events,
                self.contacts.first().map_or(self.origin, |c| c.pos),
                false,
                modifiers,
            );
        }
        if self.mode != Mode::Navigate {
            nav.started = true;
        }
        self.mode = Mode::Navigate;
    }

    fn touch(
        &mut self,
        contact: Contact,
        phase: TouchPhase,
        now: f64,
        events: &mut Vec<Event>,
        nav: &mut Navigation,
        modifiers: Modifiers,
    ) {
        if !contact.pos.is_finite() {
            self.stop(events, modifiers);
            return;
        }
        let index = self
            .contacts
            .iter()
            .position(|c| c.device == contact.device && c.id == contact.id);
        if phase == TouchPhase::Start {
            if index.is_some() || self.contacts.len() >= 10 {
                return;
            }
            if self.contacts.is_empty() {
                self.origin = contact.pos;
                self.started = now;
                self.mode = if self.pen_down {
                    Mode::Suppressed
                } else {
                    Mode::Pending
                };
            }
            self.contacts.push(contact);
            if self.contacts.len() == 2 && self.mode != Mode::Suppressed {
                self.navigate(nav, events, modifiers);
            }
            return;
        }
        let Some(index) = index else { return };
        let before = self.geometry();
        if let Some(point) = self.contacts.get_mut(index) {
            point.pos = contact.pos;
        }
        let begins_pan = self.mode == Mode::Pending && contact.pos.distance(self.origin) > SLOP;
        if begins_pan {
            self.navigate(nav, events, modifiers);
        }
        if self.mode == Mode::Navigate && phase != TouchPhase::Cancel {
            if let (Some((old, old_span)), Some((new, new_span))) = (before, self.geometry()) {
                // Include travel inside the initial slop once navigation wins.
                let old = if begins_pan { self.origin } else { old };
                nav.anchor.get_or_insert(old);
                nav.delta += new - old;
                if old_span >= 4.0 && new_span >= 4.0 {
                    let factor = new_span / old_span;
                    if factor.is_finite() && factor > 0.0 {
                        nav.zoom = Some((nav.zoom.unwrap_or(1.0) * factor).clamp(0.05, 20.0));
                    }
                }
            }
        } else if self.mode == Mode::Drag && phase == TouchPhase::Move {
            events.push(Event::PointerMoved(contact.pos));
        }
        if matches!(phase, TouchPhase::End | TouchPhase::Cancel) {
            if self.mode == Mode::Pending && phase == TouchPhase::End {
                events.push(Event::PointerMoved(contact.pos));
                button(events, contact.pos, true, modifiers);
                button(events, contact.pos, false, modifiers);
            } else if self.mode == Mode::Drag {
                events.push(Event::PointerMoved(contact.pos));
                button(events, contact.pos, false, modifiers);
            }
            self.contacts.remove(index);
            if self.contacts.is_empty() {
                nav.ended |= self.mode == Mode::Navigate;
                self.mode = Mode::Idle;
                if !self.pen_down {
                    if nav.anchor.is_some() || nav.ended {
                        // Let the canvas consume the last motion before hiding its hover point.
                        self.gone_next_frame = true;
                    } else {
                        events.push(Event::PointerGone);
                    }
                }
            } else if self.mode != Mode::Suppressed {
                // Lifting one finger never turns the remaining finger into a click or selection.
                self.mode = Mode::Navigate;
            }
        }
    }
}

/// Called by every Android shell before the application's own raw-input hook.
pub fn filter(ctx: &Context, raw: &mut RawInput) {
    let state_id = Id::new("craft.android.fingers");
    let mut fingers = ctx
        .data(|d| d.get_temp::<Fingers>(state_id))
        .unwrap_or_default();
    let mut events = Vec::with_capacity(raw.events.len() + 6);
    let mut nav = Navigation::default();
    let now = raw
        .time
        .filter(|t| t.is_finite())
        .unwrap_or_else(|| ctx.input(|i| i.time));
    if fingers.gone_next_frame {
        events.push(Event::PointerGone);
        fingers.gone_next_frame = false;
    }
    let modifiers = ctx.input(|i| i.modifiers);
    let cancelled = !raw.focused
        || raw.events.iter().any(|e| {
            matches!(
                e,
                Event::WindowFocused(false)
                    | Event::Touch {
                        phase: TouchPhase::Cancel,
                        ..
                    }
            )
        });
    if cancelled {
        fingers.stop(&mut events, modifiers);
        fingers.pen_down = false;
    } else {
        fingers.promote_hold(now, &mut events, modifiers);
    }
    for event in std::mem::take(&mut raw.events) {
        match event {
            Event::Touch {
                device_id,
                id,
                phase,
                pos,
                force: None,
            } => {
                if !cancelled {
                    fingers.touch(
                        Contact {
                            device: device_id,
                            id,
                            pos,
                        },
                        phase,
                        now,
                        &mut events,
                        &mut nav,
                        modifiers,
                    );
                }
            }
            Event::Touch {
                force: Some(_),
                phase,
                ..
            } => {
                if phase == TouchPhase::Start {
                    if fingers.mode == Mode::Drag {
                        button(
                            &mut events,
                            fingers.geometry().map_or(fingers.origin, |g| g.0),
                            false,
                            modifiers,
                        );
                    }
                    fingers.pen_down = true;
                    fingers.mode = Mode::Suppressed;
                    nav = Navigation::default();
                } else if matches!(phase, TouchPhase::End | TouchPhase::Cancel) {
                    fingers.pen_down = false;
                }
                events.push(event);
            }
            Event::WindowFocused(false) => {
                fingers.stop(&mut events, modifiers);
                fingers.pen_down = false;
                nav = Navigation::default();
                events.push(event);
            }
            _ => events.push(event),
        }
    }
    let navigation =
        fingers.mode == Mode::Navigate || nav.started || nav.ended || nav.anchor.is_some();
    if let Some(anchor) = nav.anchor.or_else(|| {
        if navigation {
            fingers.geometry().map(|g| g.0)
        } else {
            None
        }
    }) {
        events.push(Event::PointerMoved(anchor));
    }
    let wheel = |delta, phase| Event::MouseWheel {
        unit: MouseWheelUnit::Point,
        delta,
        phase,
        modifiers: Modifiers::NONE,
    };
    if nav.started {
        events.push(wheel(Vec2::ZERO, TouchPhase::Start));
    }
    if nav.delta != Vec2::ZERO {
        events.push(wheel(nav.delta, TouchPhase::Move));
    }
    if let Some(zoom) = nav.zoom.filter(|z| (z - 1.0).abs() > f32::EPSILON) {
        events.push(Event::Zoom(zoom));
    }
    if nav.ended {
        events.push(wheel(Vec2::ZERO, TouchPhase::End));
    }
    if fingers.mode == Mode::Pending {
        ctx.request_repaint_after(Duration::from_secs_f64(
            (HOLD - (now - fingers.started)).clamp(0.001, HOLD),
        ));
    }
    if fingers.gone_next_frame {
        ctx.request_repaint();
    }
    ctx.data_mut(|d| {
        d.insert_temp(Id::new(NAVIGATION), navigation);
        d.insert_temp(state_id, fingers);
    });
    raw.events = events;
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui::{pos2, vec2};

    fn run(ctx: &Context, raw: RawInput, body: impl FnMut(&mut egui::Ui)) {
        let mut output = ctx.run_ui(raw, body);
        output.textures_delta.clear();
    }

    #[test]
    fn batched_swipe_counts_each_motion_once() {
        let ctx = Context::default();
        frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
        let raw = frame(
            &ctx,
            0.1,
            vec![
                touch(1, TouchPhase::Move, 70.0, 70.0),
                touch(1, TouchPhase::Move, 90.0, 80.0),
            ],
        );
        assert_eq!(scroll(&raw), vec2(40.0, 30.0));
        run(&ctx, raw, |ui| {
            assert_eq!(ui.input(|i| i.smooth_scroll_delta), vec2(40.0, 30.0))
        });
    }

    #[test]
    fn cancelled_pending_hold_never_presses() {
        let ctx = Context::default();
        frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
        assert_eq!(
            presses(&frame(
                &ctx,
                0.7,
                vec![touch(1, TouchPhase::Cancel, 50.0, 50.0)]
            )),
            0
        );
    }

    #[test]
    fn second_finger_ends_selection_at_the_first_finger_without_a_jump() {
        let ctx = Context::default();
        frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
        frame(&ctx, 0.5, vec![]);
        frame(&ctx, 0.6, vec![touch(1, TouchPhase::Move, 80.0, 60.0)]);
        let raw = frame(&ctx, 0.7, vec![touch(2, TouchPhase::Start, 200.0, 60.0)]);
        assert!(raw.events.iter().any(|e| matches!(e, Event::PointerButton { pos, pressed: false, .. } if *pos == pos2(80.0, 60.0))));
        assert_eq!(presses(&raw), 0);
    }

    #[test]
    fn egui_receives_zoom_and_pan_without_a_pressed_pointer() {
        let ctx = Context::default();
        let raw = frame(
            &ctx,
            0.0,
            vec![
                touch(1, TouchPhase::Start, 100.0, 100.0),
                touch(2, TouchPhase::Start, 200.0, 100.0),
            ],
        );
        run(&ctx, raw, |_| {});
        let raw = frame(
            &ctx,
            0.1,
            vec![
                touch(1, TouchPhase::Move, 110.0, 120.0),
                touch(2, TouchPhase::Move, 230.0, 120.0),
            ],
        );
        run(&ctx, raw, |ui| {
            ui.input(|i| {
                assert_eq!(i.smooth_scroll_delta, vec2(20.0, 20.0));
                assert!((i.zoom_delta() - 1.2).abs() < 0.00001);
                assert!(!i.pointer.any_down());
            })
        });
    }

    fn touch(id: u64, phase: TouchPhase, x: f32, y: f32) -> Event {
        Event::Touch {
            device_id: TouchDeviceId(1),
            id: TouchId(id),
            phase,
            pos: pos2(x, y),
            force: None,
        }
    }
    fn frame(ctx: &Context, time: f64, events: Vec<Event>) -> RawInput {
        let mut raw = RawInput {
            time: Some(time),
            events,
            ..Default::default()
        };
        filter(ctx, &mut raw);
        raw
    }
    fn scroll(raw: &RawInput) -> Vec2 {
        raw.events.iter().fold(Vec2::ZERO, |sum, e| {
            sum + if let Event::MouseWheel { delta, .. } = e {
                *delta
            } else {
                Vec2::ZERO
            }
        })
    }
    fn presses(raw: &RawInput) -> usize {
        raw.events
            .iter()
            .filter(|e| matches!(e, Event::PointerButton { pressed: true, .. }))
            .count()
    }

    #[test]
    fn tap_is_deferred_but_still_clicks() {
        let ctx = Context::default();
        assert_eq!(
            presses(&frame(
                &ctx,
                0.0,
                vec![touch(1, TouchPhase::Start, 50.0, 50.0)]
            )),
            0
        );
        let raw = frame(&ctx, 0.1, vec![touch(1, TouchPhase::End, 50.0, 50.0)]);
        assert_eq!(presses(&raw), 1);
        run(&ctx, raw, |ui| {
            assert!(ui.input(|i| i.pointer.primary_clicked()))
        });
    }

    #[test]
    fn swipe_pans_and_never_presses_a_tool() {
        let ctx = Context::default();
        frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
        let raw = frame(&ctx, 0.1, vec![touch(1, TouchPhase::Move, 80.0, 70.0)]);
        assert_eq!(scroll(&raw), vec2(30.0, 20.0));
        assert_eq!(presses(&raw), 0);
        assert_eq!(
            presses(&frame(
                &ctx,
                1.0,
                vec![touch(1, TouchPhase::Move, 90.0, 80.0)]
            )),
            0
        );
        let raw = frame(&ctx, 1.1, vec![touch(1, TouchPhase::End, 100.0, 80.0)]);
        assert_eq!(scroll(&raw), vec2(10.0, 0.0));
        assert!(!raw.events.iter().any(|e| matches!(e, Event::PointerGone)));
        assert!(
            frame(&ctx, 1.2, vec![])
                .events
                .iter()
                .any(|e| matches!(e, Event::PointerGone))
        );
    }

    #[test]
    fn holding_then_dragging_is_primary_selection() {
        let ctx = Context::default();
        frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
        assert_eq!(presses(&frame(&ctx, 0.49, vec![])), 0);
        let raw = frame(&ctx, 0.5, vec![]);
        assert_eq!(presses(&raw), 1);
        run(&ctx, raw, |ui| {
            assert!(ui.input(|i| i.pointer.primary_down()))
        });
        let raw = frame(&ctx, 0.7, vec![touch(1, TouchPhase::Move, 90.0, 80.0)]);
        assert_eq!(scroll(&raw), Vec2::ZERO);
        run(&ctx, raw, |ui| {
            assert!(ui.input(|i| i.pointer.primary_down()))
        });
        let raw = frame(&ctx, 0.8, vec![touch(1, TouchPhase::End, 90.0, 80.0)]);
        run(&ctx, raw, |ui| {
            assert!(!ui.input(|i| i.pointer.primary_down() || i.pointer.primary_clicked()))
        });
    }

    #[test]
    fn pinch_and_centroid_translation_are_emitted_together() {
        let ctx = Context::default();
        frame(
            &ctx,
            0.0,
            vec![
                touch(1, TouchPhase::Start, 0.0, 0.0),
                touch(2, TouchPhase::Start, 100.0, 0.0),
            ],
        );
        let raw = frame(
            &ctx,
            0.1,
            vec![
                touch(1, TouchPhase::Move, 10.0, 20.0),
                touch(2, TouchPhase::Move, 130.0, 20.0),
            ],
        );
        assert_eq!(scroll(&raw), vec2(20.0, 20.0));
        assert_eq!(presses(&raw), 0);
        assert!(
            raw.events
                .iter()
                .any(|e| matches!(e, Event::Zoom(z) if (*z - 1.2).abs() < 0.00001))
        );
        assert!(
            raw.events
                .iter()
                .any(|e| matches!(e, Event::PointerMoved(p) if *p == pos2(50.0, 0.0)))
        );
        let raw = frame(&ctx, 0.2, vec![touch(2, TouchPhase::End, 130.0, 20.0)]);
        assert_eq!(scroll(&raw), Vec2::ZERO);
        let raw = frame(&ctx, 1.0, vec![touch(1, TouchPhase::Move, 20.0, 30.0)]);
        assert_eq!(scroll(&raw), vec2(10.0, 10.0));
        assert_eq!(presses(&raw), 0);
    }

    #[test]
    fn cancellation_and_focus_loss_do_not_leave_a_button_down() {
        for end in [
            touch(1, TouchPhase::Cancel, 50.0, 50.0),
            Event::WindowFocused(false),
        ] {
            let ctx = Context::default();
            frame(&ctx, 0.0, vec![touch(1, TouchPhase::Start, 50.0, 50.0)]);
            run(&ctx, frame(&ctx, 0.5, vec![]), |_| {});
            run(&ctx, frame(&ctx, 0.7, vec![end]), |ui| {
                assert!(!ui.input(|i| i.pointer.primary_down()))
            });
            assert_eq!(
                presses(&frame(
                    &ctx,
                    2.0,
                    vec![touch(3, TouchPhase::Start, 50.0, 50.0)]
                )),
                0
            );
        }
    }

    #[test]
    fn stylus_and_mouse_keep_their_immediate_input() {
        let ctx = Context::default();
        let pen = Event::Touch {
            device_id: TouchDeviceId(2),
            id: TouchId(1),
            phase: TouchPhase::Start,
            pos: pos2(50.0, 50.0),
            force: Some(0.6),
        };
        let mut input = vec![pen, Event::PointerMoved(pos2(50.0, 50.0))];
        button(&mut input, pos2(50.0, 50.0), true, Modifiers::NONE);
        let raw = frame(&ctx, 0.0, input.clone());
        assert_eq!(raw.events, input);
        let raw = frame(
            &ctx,
            0.1,
            vec![
                touch(4, TouchPhase::Start, 20.0, 20.0),
                touch(4, TouchPhase::Move, 90.0, 90.0),
            ],
        );
        assert_eq!(scroll(&raw), Vec2::ZERO);
        assert_eq!(presses(&raw), 0);
    }

    #[test]
    fn coincident_or_invalid_contacts_never_create_invalid_zoom() {
        let ctx = Context::default();
        frame(
            &ctx,
            0.0,
            vec![
                touch(1, TouchPhase::Start, 0.0, 0.0),
                touch(2, TouchPhase::Start, 0.0, 0.0),
            ],
        );
        let raw = frame(&ctx, 0.1, vec![touch(2, TouchPhase::Move, 100.0, 0.0)]);
        assert!(!raw.events.iter().any(|e| matches!(e, Event::Zoom(_))));
        frame(&ctx, 0.2, vec![touch(1, TouchPhase::Move, f32::NAN, 0.0)]);
        assert_eq!(presses(&frame(&ctx, 1.0, vec![])), 0);
    }
}
