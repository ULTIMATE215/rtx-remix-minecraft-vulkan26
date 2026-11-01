/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

/** Flat category tabs, scrollable option rows, and explicit Apply/Undo transactions. */
public final class RtxSettingsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtxSettingsScreen.class);
    private static final int TEXT = 0xFFE4E4E4, MUTED = 0xFFABABAB, ACCENT = 0xFF90C35A;
    private static final int ROW_HEIGHT = 25;
    private final Screen parent;
    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final SettingsDraft draft;
    private final List<Row> rows = new ArrayList<>();
    private final EnumMap<SettingCategory, Double> scrolls = new EnumMap<>(SettingCategory.class);
    private SettingCategory category = SettingCategory.PERFORMANCE;
    private SettingsLayout layout;
    private FlatButton applyButton, undoButton;
    private int contentHeight;
    private double scroll;
    private Component error;
    private boolean needsSave, materialRebuildPending, lightRebuildPending, draggingScroll;

    public RtxSettingsScreen(Screen parent, RtxSettings settings, Runnable saveSettings) {
        super(Component.translatable("rtxremix.settings.title"));
        this.parent = parent;
        this.settings = settings;
        this.saveSettings = saveSettings;
        draft = new SettingsDraft(settings);
    }

    @Override protected void init() {
        setFocused(null);
        clearWidgets();
        rows.clear();
        layout = SettingsLayout.of(width, height);
        List<SettingCategory> categories = new ArrayList<>();
        for (SettingCategory candidate : SettingCategory.values()) {
            if (!settings.category(candidate).isEmpty()) categories.add(candidate);
        }
        int tabWidth = (layout.right() - layout.left() - (categories.size() - 1) * 4) / categories.size();
        for (int i = 0; i < categories.size(); i++) {
            SettingCategory tab = categories.get(i);
            FlatButton button = new FlatButton(layout.left() + i * (tabWidth + 4), layout.tabsY(),
                    tabWidth, 22, Component.translatable(tab.labelKey()), b -> selectCategory(tab));
            button.selected = category == tab;
            addRenderableWidget(button);
        }

        int y = 4;
        int controlWidth = Math.min(146, layout.listWidth() / 2);
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() != category || settings.group(group).isEmpty()) continue;
            FlatButton reset = new FlatButton(layout.listRight() - 54, 0, 44, 16,
                    Component.translatable("rtxremix.settings.resetGroup"), b -> {
                        draft.reset(group);
                        refreshControls();
                    });
            reset.setTooltip(Tooltip.create(Component.translatable("rtxremix.settings.resetGroup.tip")));
            rows.add(new Row(y, group, null, addWidget(reset)));
            y += 25;
            for (RtxSetting<?> setting : settings.group(group)) {
                AbstractWidget control = switch (setting.kind()) {
                    case BOOL, CHOICE -> new ChoiceControl(setting, controlWidth);
                    case INT, FLOAT -> new SliderControl(setting, controlWidth);
                };
                if (!layout.hasDescription()) control.setTooltip(Tooltip.create(description(setting)));
                rows.add(new Row(y, null, setting, addWidget(control)));
                y += ROW_HEIGHT;
            }
            y += 8;
        }
        contentHeight = y;
        scroll = layout.clampScroll(scrolls.getOrDefault(category, 0.0), contentHeight);
        int w = layout.footerWidth();
        addRenderableWidget(new FlatButton(layout.left(), layout.footerY(), w, 22,
                Component.translatable("rtxremix.settings.resetAll"), b -> {
                    draft.resetAll();
                    refreshControls();
                }));
        undoButton = addRenderableWidget(new FlatButton(layout.left() + w + 6, layout.footerY(), w, 22,
                Component.translatable("rtxremix.settings.undo"), b -> {
                    draft.undo();
                    error = null;
                    refreshControls();
                }));
        applyButton = addRenderableWidget(new FlatButton(layout.left() + 2 * (w + 6), layout.footerY(), w, 22,
                Component.translatable("rtxremix.settings.apply"), b -> applyChanges()));
        addRenderableWidget(new FlatButton(layout.left() + 3 * (w + 6), layout.footerY(), w, 22,
                CommonComponents.GUI_DONE, b -> onClose()));
        refreshControls();
    }

    private void selectCategory(SettingCategory selected) {
        scrolls.put(category, scroll);
        category = selected;
        init();
    }

    private void positionRows() {
        for (Row row : rows) {
            AbstractWidget control = row.control();
            int y = layout.top() + row.offset() - (int) scroll;
            control.setY(y + 2);
            control.visible = y >= layout.top() && y + control.getHeight() + 2 <= layout.bottom();
            if (!control.visible && getFocused() == control) setFocused(null);
        }
    }

    private void refreshControls() {
        for (Row row : rows) {
            if (row.control() instanceof SliderControl slider) slider.refresh();
            if (row.control() instanceof ChoiceControl choice) choice.refresh();
        }
        updateButtons();
        positionRows();
    }

    private void updateButtons() {
        applyButton.active = draft.changed() || needsSave || materialRebuildPending || lightRebuildPending;
        undoButton.active = draft.changed();
    }

    private void applyChanges() {
        if (!applyButton.active) return;
        error = null;
        try {
            draft.apply();
        } catch (RuntimeException failure) {
            LOGGER.error("Could not apply all RTX settings", failure);
            error = Component.translatable("rtxremix.settings.applyFailed");
        } finally {
            Set<SettingGroup> applied = draft.takeAppliedGroups();
            needsSave |= !applied.isEmpty();
            lightRebuildPending |= applied.contains(SettingGroup.BLOCK_LIGHTS);
            try {
                BlockTextureManager textures = BlockTextureManager.get();
                materialRebuildPending |= textures != null && textures.commitTerrainEmissionMultiplier();
                if (materialRebuildPending) RtxRemixMod.rebuildTerrainForMaterialSettings();
                else if (lightRebuildPending) RtxRemixMod.rebuildLoadedTerrain();
                materialRebuildPending = lightRebuildPending = false;
                if (needsSave && saveSettings != null) saveSettings.run();
                needsSave = false;
            } catch (RuntimeException failure) {
                LOGGER.error("Could not commit RTX settings; Apply can retry", failure);
                error = Component.translatable("rtxremix.settings.applyFailed");
            }
            refreshControls();
        }
    }

    @Override public void onClose() {
        if (!draft.changed() && !needsSave && !materialRebuildPending && !lightRebuildPending) {
            minecraft.gui.setScreen(parent);
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(discard -> {
            if (discard) minecraft.gui.setScreen(parent);
            else minecraft.gui.setScreen(this);
        }, Component.translatable("rtxremix.settings.unsaved.title"),
                Component.translatable(needsSave || materialRebuildPending || lightRebuildPending
                        ? "rtxremix.settings.unsaved.retry" : "rtxremix.settings.unsaved.message"),
                Component.translatable("rtxremix.settings.discard"), CommonComponents.GUI_CANCEL));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= layout.left() && x < layout.listRight() && y >= layout.top() && y < layout.bottom()) {
            scroll = layout.clampScroll(scroll - vertical * ROW_HEIGHT * 2, contentHeight);
            scrolls.put(category, scroll);
            positionRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.x() >= layout.listRight() - 6 && event.x() < layout.listRight()
                && event.y() >= layout.top() && event.y() < layout.bottom() && contentHeight > layout.bodyHeight()) {
            draggingScroll = true;
            scrollToPointer(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScroll) { scrollToPointer(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll) { draggingScroll = false; return true; }
        return super.mouseReleased(event);
    }

    private void scrollToPointer(double y) {
        int track = layout.bodyHeight(), thumb = Math.max(12, track * track / contentHeight);
        scroll = layout.clampScroll((y - layout.top() - thumb / 2.0) / (track - thumb) * (contentHeight - track), contentHeight);
        scrolls.put(category, scroll);
        positionRows();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            scroll = layout.clampScroll(scroll + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * layout.bodyHeight(), contentHeight);
            scrolls.put(category, scroll);
            positionRows();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            // Off-screen controls still participate in Tab order; reveal the selected row.
            int step = event.hasShiftDown() ? -1 : 1;
            int current = children().indexOf(getFocused());
            int next = current < 0 ? (step > 0 ? -1 : 0) : current;
            for (int i = 0; i < children().size(); i++) {
                next = Math.floorMod(next + step, children().size());
                if (!(children().get(next) instanceof AbstractWidget widget) || widget.active) break;
            }
            var target = children().get(next);
            for (Row row : rows) if (row.control() == target) {
                if (row.offset() < scroll) scroll = row.offset();
                if (row.offset() + ROW_HEIGHT > scroll + layout.bodyHeight()) scroll = row.offset() + ROW_HEIGHT - layout.bodyHeight();
                scroll = layout.clampScroll(scroll, contentHeight);
                scrolls.put(category, scroll);
                positionRows();
            }
            setFocused(target);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override protected void repositionElements() {
        scrolls.put(category, scroll);
        init();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xDA101010);
        graphics.enableScissor(layout.left(), 0, layout.right(), layout.tabsY());
        graphics.text(font, title, layout.left(), 12, TEXT, false);
        Component status = error != null ? error : draft.changed() || needsSave
                ? Component.translatable("rtxremix.settings.pending") : Component.empty();
        graphics.enableScissor(layout.left() + font.width(title) + 10, 0, layout.right(), layout.tabsY());
        graphics.text(font, status, Math.max(layout.left() + font.width(title) + 10, layout.right() - font.width(status)), 12,
                error == null ? ACCENT : 0xFFFF8888, false);
        graphics.disableScissor();
        graphics.disableScissor();
        graphics.fill(layout.left(), layout.top(), layout.listRight(), layout.bottom(), 0xC0161616);
        RtxSetting<?> selected = null;
        graphics.enableScissor(layout.left(), layout.top(), layout.listRight(), layout.bottom());
        for (Row row : rows) {
            int y = layout.top() + row.offset() - (int) scroll;
            if (y + ROW_HEIGHT < layout.top() || y >= layout.bottom()) continue;
            if (row.setting() == null) {
                graphics.text(font, Component.translatable(row.group().labelKey()), layout.left() + 8, y + 6, ACCENT, false);
            } else {
                boolean hover = mouseX >= layout.left() && mouseX < layout.listRight()
                        && mouseY >= Math.max(y, layout.top()) && mouseY < Math.min(y + ROW_HEIGHT - 1, layout.bottom());
                graphics.fill(layout.left() + 4, y, layout.listRight() - 6, y + ROW_HEIGHT - 2,
                        hover || row.control().isFocused() ? 0xFF373737 : 0xD0252525);
                if (draft.changed(row.setting())) graphics.fill(layout.left() + 4, y, layout.left() + 6, y + ROW_HEIGHT - 2, ACCENT);
                graphics.enableScissor(layout.left() + 9, y, row.control().getX() - 5, y + ROW_HEIGHT - 2);
                graphics.text(font, Component.translatable(row.setting().labelKey()), layout.left() + 10, y + 7, TEXT, false);
                graphics.disableScissor();
                if (hover || row.control().isFocused()) selected = row.setting();
            }
            row.control().extractRenderState(graphics, mouseX, mouseY, delta);
        }
        graphics.disableScissor();
        if (contentHeight > layout.bodyHeight()) {
            int track = layout.bodyHeight(), thumb = Math.max(12, track * track / contentHeight);
            int y = layout.top() + (int) ((track - thumb) * scroll / (contentHeight - track));
            graphics.fill(layout.listRight() - 3, layout.top(), layout.listRight(), layout.bottom(), 0xFF282828);
            graphics.fill(layout.listRight() - 3, y, layout.listRight(), y + thumb, 0xFF808080);
        }
        if (layout.hasDescription()) {
            graphics.fill(layout.descriptionX(), layout.top(), layout.right(), layout.bottom(), 0xDB202020);
            graphics.enableScissor(layout.descriptionX(), layout.top(), layout.right(), layout.bottom());
            Component heading = selected == null ? Component.translatable(category.labelKey()) : Component.translatable(selected.labelKey());
            graphics.text(font, heading, layout.descriptionX() + 10, layout.top() + 12, TEXT, false);
            graphics.textWithWordWrap(font, selected == null ? Component.translatable("rtxremix.settings.help") : description(selected),
                    layout.descriptionX() + 10, layout.top() + 34, layout.right() - layout.descriptionX() - 20, MUTED, false);
            graphics.disableScissor();
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private Component description(RtxSetting<?> setting) {
        return CommonComponents.joinLines(Component.translatable(setting.tooltipKey()), Component.empty(),
                Component.translatable("rtxremix.settings.tooltip.default", valueText(setting, setting.defaultValue())));
    }

    private static Component valueText(RtxSetting<?> setting, Object value) {
        return switch (setting.kind()) {
            case BOOL -> RtxOptionFactory.booleanValueText(setting.id(), (Boolean) value);
            case INT -> RtxOptionFactory.intValueText(setting.id(), (Integer) value);
            case FLOAT -> RtxOptionFactory.floatValueText(setting.id(), (Double) value);
            case CHOICE -> Component.translatable(setting.choiceLabelKey((String) value));
        };
    }

    private class FlatButton extends AbstractWidget {
        boolean selected;
        private final Consumer<FlatButton> action;
        FlatButton(int x, int y, int w, int h, Component label, Consumer<FlatButton> action) {
            super(x, y, w, h, label);
            this.action = action;
        }
        public void onPress(InputWithModifiers input) { action.accept(this); }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { onPress(event); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (active && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER
                    || event.key() == GLFW.GLFW_KEY_SPACE)) {
                playDownSound(minecraft.getSoundManager());
                onPress(event);
                return true;
            }
            return false;
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), selected ? 0xFF3B502C
                    : !active ? 0xDD202020 : isHoveredOrFocused() ? 0xFF555555 : 0xFF353535);
            if (selected || isFocused()) graphics.fill(getX(), getBottom() - 2, getRight(), getBottom(), ACCENT);
            graphics.enableScissor(getX() + 3, getY(), getRight() - 3, getBottom());
            graphics.text(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2,
                    getY() + (getHeight() - 8) / 2, active ? TEXT : 0xFF777777, false);
            graphics.disableScissor();
        }
    }

    private final class ChoiceControl extends FlatButton {
        private final RtxSetting<?> setting;
        ChoiceControl(RtxSetting<?> setting, int width) {
            super(layout.listRight() - width - 10, 0, width, 20, Component.empty(), b -> {});
            this.setting = setting;
            refresh();
        }
        @Override public void onPress(InputWithModifiers input) {
            if (setting.kind() == RtxSetting.Kind.BOOL) draft.set(setting, !(Boolean) draft.get(setting));
            else {
                List<String> choices = setting.choices();
                int step = input.hasShiftDown() ? -1 : 1;
                draft.set(setting, choices.get(Math.floorMod(choices.indexOf(draft.get(setting)) + step, choices.size())));
            }
            refreshControls();
        }
        void refresh() {
            setMessage(valueText(setting, draft.get(setting)));
            selected = setting.kind() == RtxSetting.Kind.BOOL && (Boolean) draft.get(setting);
        }
    }

    private final class SliderControl extends AbstractSliderButton {
        private final RtxSetting<?> setting;
        SliderControl(RtxSetting<?> setting, int width) {
            super(layout.listRight() - width - 10, 0, width, 20, Component.empty(), 0);
            this.setting = setting;
            refresh();
        }
        @SuppressWarnings("unchecked") void refresh() {
            if (setting.kind() == RtxSetting.Kind.FLOAT) {
                value = RtxOptionFactory.sliderValueFromFloat(setting.id(), (Double) draft.get(setting), setting.min(), setting.max());
            } else {
                RtxSetting<Integer> s = (RtxSetting<Integer>) setting;
                int sentinel = RtxOptionFactory.sentinelSliderValue(s.id());
                int min = sentinel > 0 ? 1 : (int) s.min() / s.step();
                int max = sentinel > 0 ? sentinel : (int) s.max() / s.step();
                value = (double) (RtxOptionFactory.sliderValueFromInt(s, (Integer) draft.get(s)) - min) / (max - min);
            }
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(valueText(setting, draft.get(setting))); }
        @Override @SuppressWarnings("unchecked") protected void applyValue() {
            if (setting.kind() == RtxSetting.Kind.FLOAT) {
                draft.set(setting, RtxOptionFactory.floatValueFromSlider(setting.id(), value, setting.min(), setting.max()));
            } else {
                RtxSetting<Integer> s = (RtxSetting<Integer>) setting;
                int sentinel = RtxOptionFactory.sentinelSliderValue(s.id());
                int min = sentinel > 0 ? 1 : (int) s.min() / s.step();
                int max = sentinel > 0 ? sentinel : (int) s.max() / s.step();
                draft.set(s, RtxOptionFactory.intValueFromSlider(s, min + (int) Math.round(value * (max - min))));
            }
            updateButtons();
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), !active ? 0xFF202020 : isHoveredOrFocused() ? 0xFF444444 : 0xFF303030);
            int barY = getBottom() - 3;
            graphics.fill(getX() + 4, barY, getRight() - 4, barY + 1, 0xFF707070);
            int handle = getX() + 4 + (int) ((getWidth() - 8) * value);
            graphics.fill(getX() + 4, barY, handle, barY + 1, ACCENT);
            graphics.fill(handle - 1, barY - 2, handle + 1, barY + 2, ACCENT);
            graphics.text(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2, getY() + 3, active ? TEXT : 0xFF777777, false);
        }
    }

    private record Row(int offset, SettingGroup group, RtxSetting<?> setting, AbstractWidget control) {}
}
