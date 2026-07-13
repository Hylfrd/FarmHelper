package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hylfrd.farmhelper.ui.settings.SettingDefinition;
import dev.hylfrd.farmhelper.ui.settings.SettingsDraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Creates project-owned controls exclusively from Minecraft's native widgets. */
final class NativeSettingControlFactory {
    private NativeSettingControlFactory() {
    }

    static AbstractWidget create(
            SettingDefinition<?> definition,
            SettingsDraft draft,
            Font font,
            int x,
            int y,
            int width,
            Runnable changed,
            Runnable refresh,
            Consumer<String> invalid,
            Consumer<SettingDefinition<Integer>> recordKey
    ) {
        AbstractWidget widget = switch (definition.kind()) {
            case BOOLEAN -> booleanControl(cast(definition), draft, x, y, width, changed);
            case INTEGER -> integerControl(cast(definition), draft, font, x, y, width, changed, invalid);
            case DECIMAL -> decimalControl(cast(definition), draft, font, x, y, width, changed, invalid);
            case ENUM -> enumControl(definition, draft, x, y, width, changed);
            case STRING -> stringControl(cast(definition), draft, font, x, y, width, changed, invalid);
            case COLOR -> colorControl(cast(definition), draft, font, x, y, width, changed, invalid);
            case KEYBIND -> keybindControl(cast(definition), draft, x, y, width, recordKey);
            case ACTION -> actionControl(definition, draft, x, y, width, changed, refresh);
        };
        widget.setTooltip(Tooltip.create(Component.literal(definition.description())));
        return widget;
    }

    private static Button booleanControl(
            SettingDefinition<Boolean> definition, SettingsDraft draft,
            int x, int y, int width, Runnable changed
    ) {
        return Button.builder(booleanMessage(draft.read(definition)), button -> {
            boolean next = !draft.read(definition);
            draft.write(definition, next);
            button.setMessage(booleanMessage(next));
            changed.run();
        }).bounds(x, y, width, 20).build();
    }

    private static EditBox integerControl(
            SettingDefinition<Integer> definition, SettingsDraft draft, Font font,
            int x, int y, int width, Runnable changed, Consumer<String> invalid
    ) {
        EditBox box = editBox(font, x, y, width, Integer.toString(draft.read(definition)), definition.label());
        box.setResponder(text -> updateText(box, text, value -> draft.write(definition, Integer.parseInt(value)),
                changed, invalid));
        return box;
    }

    private static EditBox decimalControl(
            SettingDefinition<Double> definition, SettingsDraft draft, Font font,
            int x, int y, int width, Runnable changed, Consumer<String> invalid
    ) {
        EditBox box = editBox(font, x, y, width, formatDecimal(draft.read(definition)), definition.label());
        box.setResponder(text -> updateText(box, text, value -> draft.write(definition, Double.parseDouble(value)),
                changed, invalid));
        return box;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Button enumControl(
            SettingDefinition<?> definition, SettingsDraft draft,
            int x, int y, int width, Runnable changed
    ) {
        SettingDefinition raw = definition;
        return Button.builder(enumMessage((Enum<?>) draft.read(raw)), button -> {
            List<?> choices = definition.choices();
            int nextIndex = (choices.indexOf(draft.read(raw)) + 1) % choices.size();
            Enum<?> next = (Enum<?>) choices.get(nextIndex);
            draft.write(raw, next);
            button.setMessage(enumMessage(next));
            changed.run();
        }).bounds(x, y, width, 20).build();
    }

    private static EditBox stringControl(
            SettingDefinition<String> definition, SettingsDraft draft, Font font,
            int x, int y, int width, Runnable changed, Consumer<String> invalid
    ) {
        EditBox box = editBox(font, x, y, width, draft.read(definition), definition.label());
        box.setMaxLength(256);
        box.setResponder(text -> updateText(box, text, value -> draft.write(definition, value), changed, invalid));
        return box;
    }

    private static EditBox colorControl(
            SettingDefinition<Integer> definition, SettingsDraft draft, Font font,
            int x, int y, int width, Runnable changed, Consumer<String> invalid
    ) {
        EditBox box = editBox(font, x, y, width, String.format(Locale.ROOT, "#%06X", draft.read(definition)),
                definition.label());
        box.setMaxLength(7);
        box.setResponder(text -> updateText(box, text, value -> {
            String normalized = value.startsWith("#") ? value.substring(1) : value;
            draft.write(definition, Integer.parseInt(normalized, 16));
        }, changed, invalid));
        return box;
    }

    private static Button keybindControl(
            SettingDefinition<Integer> definition, SettingsDraft draft,
            int x, int y, int width, Consumer<SettingDefinition<Integer>> recordKey
    ) {
        return Button.builder(keyMessage(draft.read(definition)), button -> recordKey.accept(definition))
                .bounds(x, y, width, 20)
                .build();
    }

    private static Button actionControl(
            SettingDefinition<?> definition, SettingsDraft draft,
            int x, int y, int width, Runnable changed, Runnable refresh
    ) {
        return Button.builder(Component.literal(definition.label()), button -> {
            draft.activate(definition);
            changed.run();
            refresh.run();
        }).bounds(x, y, width, 20).build();
    }

    private static EditBox editBox(Font font, int x, int y, int width, String value, String label) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(label));
        box.setValue(value);
        box.setTextColor(0xFFE0E0E0);
        return box;
    }

    private static void updateText(
            EditBox box, String text, Consumer<String> update,
            Runnable changed, Consumer<String> invalid
    ) {
        try {
            update.accept(text.strip());
            box.setTextColor(0xFFE0E0E0);
            changed.run();
        } catch (IllegalArgumentException exception) {
            box.setTextColor(0xFFFF7777);
            invalid.accept(exception.getMessage() == null ? "Invalid value." : exception.getMessage());
        }
    }

    private static Component booleanMessage(boolean value) {
        return Component.literal(value ? "On" : "Off");
    }

    private static Component enumMessage(Enum<?> value) {
        return Component.literal(value.name().toLowerCase(Locale.ROOT));
    }

    static Component keyMessage(int keyCode) {
        return keyCode == -1
                ? Component.literal("Unbound")
                : InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName();
    }

    private static String formatDecimal(double value) {
        return value == Math.rint(value)
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> SettingDefinition<T> cast(SettingDefinition<?> definition) {
        return (SettingDefinition<T>) definition;
    }
}
