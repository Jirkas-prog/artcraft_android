//! Android chrome uses the existing commands and close/save safeguards.
use crate::canvas::Fit;
use crate::{Mode, PdfCraftApp, RightPanel, icons, theme};
use egui::{Align, Button, Frame, Margin, Stroke, Ui, vec2};

#[path = "tablet_layout.rs"]
mod layout;
use layout::{BAR, TOUCH, row};

fn frame(ui: &Ui) -> Frame {
    let t = theme::Tokens::get(ui.ctx());
    Frame::NONE
        .fill(t.chrome)
        .inner_margin(Margin::symmetric(4, 4))
        .stroke(Stroke::new(1.0, t.divider))
}

fn icon(ui: &mut Ui, name: &str, label: &str) -> bool {
    icons::button(ui, name, TOUCH, false, label).clicked()
}

pub fn mode_bar(app: &mut PdfCraftApp, ui: &mut Ui) {
    egui::Panel::top("tablet-modes")
        .exact_size(BAR)
        .frame(frame(ui))
        .show(ui, |ui| {
            row(ui, "tablet-mode-scroll", |ui| {
                // The original menu retains every command, including Preferences and printing.
                super::main_menu(app, ui);
                for (mode, label) in [
                    (Mode::AllTools, "All tools"),
                    (Mode::Read, "Read"),
                    (Mode::Edit, "Edit"),
                    (Mode::Convert, "Convert"),
                    (Mode::Sign, "E-Sign"),
                ] {
                    if ui
                        .add_sized(
                            vec2(TOUCH, TOUCH),
                            Button::new(tl!(label)).selected(app.mode == mode),
                        )
                        .clicked()
                    {
                        app.switch_mode(mode, true);
                    }
                }
                if icon(ui, "search", tl!("Find tools and commands")) {
                    app.palette_open = true;
                }
                ui.add_enabled_ui(app.active.is_some(), |ui| {
                    if icon(ui, "save", tl!("Save")) {
                        app.run_command("file.save");
                    }
                    if icon(ui, "printer", tl!("Print")) {
                        app.run_command("print.dialog");
                    }
                    if icon(ui, "info", tl!("Document properties")) {
                        app.run_command("file.properties");
                    }
                });
            });
        });
}

pub fn right_rail(app: &mut PdfCraftApp, ui: &mut Ui) {
    let Some((index, id)) = app.active_ids() else {
        return;
    };
    let Some(doc) = app.session.get(id) else {
        return;
    };
    let pages = doc.info.pages.len();
    egui::Panel::bottom("tablet-navigation")
        .exact_size(BAR)
        .frame(frame(ui))
        .show(ui, |ui| {
            row(ui, "tablet-navigation-scroll", |ui| {
                let panels = icons::button(
                    ui,
                    "panel-right",
                    TOUCH,
                    app.right.is_some(),
                    tl!("Side panels"),
                );
                egui::Popup::menu(&panels).show(|ui| {
                    ui.spacing_mut().interact_size.y = TOUCH;
                    for (panel, label) in [
                        (RightPanel::Comments, "Comments"),
                        (RightPanel::Bookmarks, "Bookmarks"),
                        (RightPanel::Pages, "Page thumbnails"),
                        (RightPanel::Fields, "Form fields"),
                        (RightPanel::Layers, "Layers"),
                        (RightPanel::Attachments, "Attachments"),
                        (RightPanel::Signatures, "Signatures"),
                        (RightPanel::Accessibility, "Accessibility Checker"),
                        (RightPanel::Tags, "Tags"),
                        (RightPanel::Search, "Search"),
                        (RightPanel::Compare, "Compare"),
                    ] {
                        if ui
                            .selectable_label(app.right == Some(panel), tl!(label))
                            .clicked()
                        {
                            app.choose_right_panel(if app.right == Some(panel) {
                                None
                            } else {
                                Some(panel)
                            });
                            ui.close();
                        }
                    }
                });
                let Some(view) = app.views.get_mut(index) else {
                    return;
                };
                ui.add_enabled_ui(view.current > 0, |ui| {
                    if icon(ui, "chevron-up", tl!("Previous page")) {
                        view.step_page(false);
                    }
                });
                let page_id = view.page_input_id();
                let reselect = std::mem::take(&mut view.page_input_reselect);
                if !ui.memory(|m| m.has_focus(page_id)) {
                    view.page_input = view.current_page_label(
                        app.session
                            .get(id)
                            .and_then(|d| d.info.pages.get(view.current))
                            .map(|p| p.label.as_str()),
                    );
                } else if reselect {
                    let mut state =
                        egui::TextEdit::load_state(ui.ctx(), page_id).unwrap_or_default();
                    state
                        .cursor
                        .set_char_range(Some(egui::text::CCursorRange::two(
                            egui::text::CCursor::new(0),
                            egui::text::CCursor::new(view.page_input.chars().count()),
                        )));
                    state.store(ui.ctx(), page_id);
                }
                let edit = egui::TextEdit::singleline(&mut view.page_input)
                    .id(page_id)
                    .desired_width(64.0)
                    .min_size(vec2(64.0, TOUCH))
                    .vertical_align(Align::Center)
                    .horizontal_align(Align::Center);
                let response = ui.add(edit).on_hover_text(tl!(
                    "Current page — type a page number or label (such as iv) and press Enter"
                ));
                if response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                    // Page labels are needed only when submitting, not copied every render frame.
                    let labels = app
                        .session
                        .get(id)
                        .map(|d| {
                            d.info
                                .pages
                                .iter()
                                .map(|p| p.label.clone())
                                .collect::<Vec<_>>()
                        })
                        .unwrap_or_default();
                    let typed = view.page_input.clone();
                    view.go_to_typed(&typed, &labels);
                }
                ui.label(format!("/ {pages}"));
                ui.add_enabled_ui(view.current.saturating_add(1) < pages, |ui| {
                    if icon(ui, "chevron-down", tl!("Next page")) {
                        view.step_page(true);
                    }
                });
                if icon(ui, "zoom-out", tl!("Zoom out")) {
                    view.zoom_step(false);
                }
                if icon(ui, "zoom-in", tl!("Zoom in")) {
                    view.zoom_step(true);
                }
                let zoom_id = egui::Id::new("zoom-input");
                let draft_id = zoom_id.with("draft");
                let mut zoom = ui
                    .data(|d| d.get_temp::<String>(draft_id))
                    .unwrap_or_else(|| format!("{:.0}%", view.zoom * 100.0));
                let response = ui
                    .add(
                        egui::TextEdit::singleline(&mut zoom)
                            .id(zoom_id)
                            .desired_width(64.0)
                            .min_size(vec2(64.0, TOUCH))
                            .vertical_align(Align::Center)
                            .horizontal_align(Align::Center),
                    )
                    .on_hover_text(tl!("Zoom — type a percentage and press Enter"));
                if response.gained_focus() {
                    let mut state =
                        egui::TextEdit::load_state(ui.ctx(), zoom_id).unwrap_or_default();
                    state
                        .cursor
                        .set_char_range(Some(egui::text::CCursorRange::two(
                            egui::text::CCursor::new(0),
                            egui::text::CCursor::new(zoom.chars().count()),
                        )));
                    state.store(ui.ctx(), zoom_id);
                }
                if response.has_focus() {
                    ui.data_mut(|d| d.insert_temp(draft_id, zoom.clone()));
                } else {
                    ui.data_mut(|d| d.remove::<String>(draft_id));
                    if response.lost_focus()
                        && ui.input(|i| i.key_pressed(egui::Key::Enter))
                        && let Some(zoom) = crate::canvas::DocView::parse_zoom_percent(&zoom)
                    {
                        view.set_zoom(zoom);
                    }
                }
                if icon(ui, "maximize", tl!("Toggle fit page / fit width")) {
                    view.fit = if view.fit == Fit::Width {
                        Fit::Page
                    } else {
                        Fit::Width
                    };
                    view.goto = Some((view.current, 0.0));
                }
                if icon(ui, "rotate-cw", tl!("Rotate view clockwise")) {
                    view.rotate_view(true);
                }
                let display =
                    icons::button(ui, view.layout.icon(), TOUCH, false, tl!("Page display"));
                let command = egui::Popup::menu(&display)
                    .show(|ui| {
                        ui.spacing_mut().interact_size.y = TOUCH;
                        super::rail_view_menu(ui, view)
                    })
                    .and_then(|r| r.inner);
                if let Some(command) = command {
                    app.execute(command);
                }
            });
        });
}
