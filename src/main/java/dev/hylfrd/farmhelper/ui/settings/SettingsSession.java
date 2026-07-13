package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Search, category, draft and save state with no rendering dependency. */
public final class SettingsSession {
    private final SettingsCatalog catalog;
    private final SettingsDraft draft;
    private SettingCategory category = SettingCategory.ROTATION;
    private String query = "";
    private String feedback = "";

    public SettingsSession(SettingsCatalog catalog, FarmHelperConfig liveConfig) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        draft = new SettingsDraft(liveConfig);
    }

    public SettingsDraft draft() {
        return draft;
    }

    public SettingCategory category() {
        return category;
    }

    public void selectCategory(SettingCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        feedback = "";
    }

    public String query() {
        return query;
    }

    public void setQuery(String query) {
        this.query = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
    }

    public List<SettingDefinition<?>> visibleSettings() {
        return catalog.definitions().stream()
                .filter(definition -> definition.category() == category)
                .filter(definition -> definition.matches(query))
                .toList();
    }

    public void resetCategory() {
        draft.resetCategory(catalog, category);
        feedback = category.label() + " draft reset.";
    }

    public boolean save(SettingsSaveService service) {
        Objects.requireNonNull(service, "service");
        if (!draft.dirty()) {
            feedback = "No changes to save.";
            return true;
        }
        if (!service.save(draft.snapshot())) {
            feedback = "Save failed; the live configuration was not changed.";
            return false;
        }
        draft.markSaved();
        feedback = "Settings saved.";
        return true;
    }

    public String feedback() {
        return feedback;
    }
}
