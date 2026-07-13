package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.ui.settings.SettingCategory;
import dev.hylfrd.farmhelper.ui.settings.SettingDefinition;
import dev.hylfrd.farmhelper.ui.settings.SettingsCatalog;
import dev.hylfrd.farmhelper.ui.settings.SettingsSession;
import dev.hylfrd.farmhelper.ui.settings.SettingsViewport;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Functional, project-owned settings screen built only from Minecraft Screen API widgets. */
public final class FarmHelperSettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 42;
    private final Screen parent;
    private final FarmHelperClientRuntime runtime;
    private final KeyMapping openKey;
    private final SettingsSession session;
    private final SettingsViewport viewport = new SettingsViewport();
    private EditBox search;
    private SettingDefinition<Integer> recordingKey;
    private String transientFeedback = "";
    private final Set<String> invalidSettings = new HashSet<>();
    private boolean searchRebuildRequested;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;
    private int controlX;
    private int rowControlOffset;
    private int visibleRows;

    public FarmHelperSettingsScreen(Screen parent, FarmHelperClientRuntime runtime, KeyMapping openKey) {
        super(Component.literal("FarmHelper Settings"));
        this.parent = parent;
        this.runtime = runtime;
        this.openKey = openKey;
        session = new SettingsSession(SettingsCatalog.standard(), runtime.configSnapshot());
    }

    @Override
    protected void init() {
        invalidSettings.clear();
        Layout layout = Layout.compute(width, height);
        contentX = layout.contentX();
        contentY = layout.contentY();
        contentWidth = layout.contentWidth();
        contentHeight = layout.contentHeight();
        visibleRows = Math.max(1, contentHeight / ROW_HEIGHT);
        rowControlOffset = Math.max(0, Math.min(10, contentHeight - 20));

        search = new EditBox(font, contentX, 8, contentWidth, 20, Component.literal("Search settings"));
        search.setHint(Component.literal("Search settings..."));
        search.setMaxLength(80);
        search.setValue(session.query());
        search.setResponder(value -> {
            if (!value.equals(session.query())) {
                session.setQuery(value);
                viewport.reset();
                searchRebuildRequested = true;
            }
        });
        addRenderableWidget(search);

        int categoryY = 38;
        for (SettingCategory category : SettingCategory.values()) {
            String marker = category == session.category() ? "> " : "";
            addRenderableWidget(Button.builder(Component.literal(marker + category.label()), button -> {
                session.selectCategory(category);
                viewport.reset();
                rebuildWidgets();
            }).bounds(layout.sidebarX(), categoryY, layout.sidebarWidth(), 20).build());
            categoryY += 24;
        }

        List<SettingDefinition<?>> visible = session.visibleSettings();
        viewport.clamp(visible.size(), visibleRows);
        int end = Math.min(visible.size(), viewport.firstRow() + visibleRows);
        int controlWidth = Math.min(contentWidth, Math.max(48, Math.min(132, contentWidth / 2)));
        controlX = contentX + contentWidth - controlWidth;
        for (int index = viewport.firstRow(); index < end; index++) {
            SettingDefinition<?> definition = visible.get(index);
            int rowY = contentY + (index - viewport.firstRow()) * ROW_HEIGHT;
            AbstractWidget widget = NativeSettingControlFactory.create(
                    definition, session.draft(), font, controlX, rowY + rowControlOffset, controlWidth,
                    () -> onDraftChanged(definition.id()), this::rebuildWidgets,
                    message -> showInvalid(definition.id(), message), this::startRecording);
            addRenderableWidget(widget);
        }

        int bottomY = Math.max(4, height - 26);
        int buttonWidth = Math.max(1, Math.min(90, (contentWidth - 8) / 3));
        addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
            session.resetCategory();
            transientFeedback = "";
            rebuildWidgets();
        }).bounds(contentX, bottomY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(contentX + buttonWidth + 4, bottomY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(contentX + (buttonWidth + 4) * 2, bottomY, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD0101010);
        graphics.fill(contentX - 6, contentY - 6, contentX + contentWidth + 6,
                Math.min(height - 30, contentY + contentHeight + 4), 0xB0202020);
        int titleWidth = Math.max(0, contentX - 16);
        if (titleWidth > 0) {
            graphics.text(font, Component.literal(font.plainSubstrByWidth(title.getString(), titleWidth)),
                    8, 12, 0xFFFFFFFF, true);
        }

        List<SettingDefinition<?>> visible = session.visibleSettings();
        int end = Math.min(visible.size(), viewport.firstRow() + visibleRows);
        int textWidth = Math.max(0, controlX - contentX - 8);
        for (int index = viewport.firstRow(); index < end; index++) {
            SettingDefinition<?> definition = visible.get(index);
            int y = contentY + (index - viewport.firstRow()) * ROW_HEIGHT;
            if (textWidth > 0) {
                graphics.text(font, Component.literal(font.plainSubstrByWidth(definition.label(), textWidth)),
                        contentX + 4, y + 3, 0xFFFFFFFF, false);
                if (contentHeight >= 34) {
                    graphics.text(font,
                            Component.literal(font.plainSubstrByWidth(definition.description(), textWidth)),
                            contentX + 4, y + 18, 0xFFAAAAAA, false);
                }
            }
        }
        if (visible.isEmpty()) {
            graphics.centeredText(font, Component.literal("No matching settings"),
                    contentX + contentWidth / 2, contentY + 16, 0xFFAAAAAA);
        }

        String feedback = !transientFeedback.isBlank() ? transientFeedback : session.feedback();
        if (recordingKey != null) {
            feedback = "Press a key, Delete to unbind, or Escape to cancel.";
        }
        if (!feedback.isBlank()) {
            int feedbackY = contentHeight < ROW_HEIGHT ? 30 : Math.max(30, height - 38);
            String visibleFeedback = font.plainSubstrByWidth(feedback, contentWidth);
            graphics.text(font, Component.literal(visibleFeedback), contentX, feedbackY,
                    feedback.startsWith("Save failed") || feedback.startsWith("Invalid")
                            ? 0xFFFF7777 : 0xFF99DD99, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= contentX && mouseX <= contentX + contentWidth
                && mouseY >= contentY && mouseY <= contentY + contentHeight) {
            int direction = vertical > 0.0 ? -1 : vertical < 0.0 ? 1 : 0;
            if (direction != 0) {
                viewport.scroll(direction, session.visibleSettings().size(), visibleRows);
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (recordingKey != null) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                recordingKey = null;
                transientFeedback = "Key recording cancelled.";
                rebuildWidgets();
                return true;
            }
            int key = event.key() == InputConstants.KEY_DELETE ? -1 : event.key();
            try {
                session.draft().write(recordingKey, key);
                recordingKey = null;
                transientFeedback = "Key recorded in draft; press Save to apply.";
                rebuildWidgets();
            } catch (IllegalArgumentException exception) {
                showInvalid(recordingKey.id(), exception.getMessage());
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        if (searchRebuildRequested) {
            searchRebuildRequested = false;
            rebuildWidgets();
            search.setFocused(true);
            setFocused(search);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void onDraftChanged(String settingId) {
        invalidSettings.remove(settingId);
        transientFeedback = session.draft().dirty() ? "Unsaved draft changes." : "";
    }

    private void showInvalid(String settingId, String message) {
        invalidSettings.add(settingId);
        transientFeedback = "Invalid value: " + (message == null ? "validation failed." : message);
    }

    private void startRecording(SettingDefinition<Integer> definition) {
        recordingKey = definition;
        transientFeedback = "";
        rebuildWidgets();
    }

    private void save() {
        transientFeedback = "";
        if (!invalidSettings.isEmpty()) {
            transientFeedback = "Invalid value: correct highlighted fields before saving.";
            return;
        }
        if (session.save(runtime::saveConfig)) {
            int keyCode = session.draft().read(SettingsCatalog.OPEN_SETTINGS_KEY);
            openKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(keyCode));
            KeyMapping.resetMapping();
        }
        rebuildWidgets();
    }

    record Layout(int sidebarX, int sidebarWidth, int contentX, int contentY, int contentWidth, int contentHeight) {
        static Layout compute(int width, int height) {
            int margin = width < 420 ? Math.max(2, Math.min(6, width / 16)) : 18;
            int available = Math.max(1, width - margin * 2);
            int gap = available >= 120 ? 8 : Math.min(4, Math.max(0, available / 12));
            int minimumContent = Math.min(96, Math.max(1, available * 2 / 3));
            int maximumSidebar = Math.max(1, available - gap - minimumContent);
            int sidebar = Math.min(maximumSidebar, Math.max(48, Math.min(126, available / 4)));
            int contentWidth = Math.max(1, available - sidebar - gap);
            int contentX = margin + sidebar + gap;
            int contentY = 38;
            int bottomY = Math.max(4, height - 26);
            int contentHeight = Math.max(1, bottomY - contentY - 4);
            return new Layout(margin, sidebar, contentX, contentY, contentWidth, contentHeight);
        }
    }
}
