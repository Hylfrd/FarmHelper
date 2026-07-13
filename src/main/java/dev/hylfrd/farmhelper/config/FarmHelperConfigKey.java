package dev.hylfrd.farmhelper.config;

/** Stable command-facing identifiers for settings currently present in the typed config model. */
public enum FarmHelperConfigKey {
    TARGET_YAW("targetYaw", -180.0F, 180.0F) {
        @Override
        public float read(FarmHelperConfig config) {
            return config.targetYaw();
        }

        @Override
        public void write(FarmHelperConfig config, float value) {
            config.setTargetYaw(value);
        }
    },
    TARGET_PITCH("targetPitch", -90.0F, 90.0F) {
        @Override
        public float read(FarmHelperConfig config) {
            return config.targetPitch();
        }

        @Override
        public void write(FarmHelperConfig config, float value) {
            config.setTargetPitch(value);
        }
    };

    private final String commandName;
    private final float minimum;
    private final float maximum;

    FarmHelperConfigKey(String commandName, float minimum, float maximum) {
        this.commandName = commandName;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String commandName() {
        return commandName;
    }

    public float minimum() {
        return minimum;
    }

    public float maximum() {
        return maximum;
    }

    public abstract float read(FarmHelperConfig config);

    public abstract void write(FarmHelperConfig config, float value);

    public void reset(FarmHelperConfig config) {
        write(config, read(new FarmHelperConfig()));
    }
}
