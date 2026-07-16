package dev.hylfrd.farmhelper.ui.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

import static com.mojang.brigadier.arguments.FloatArgumentType.floatArg;
import static com.mojang.brigadier.arguments.FloatArgumentType.getFloat;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

/** Version-independent Brigadier tree. Executable nodes only parse, format, and invoke the service port. */
public final class FarmHelperCommandTree {
    private static final String VALUE_ARGUMENT = "value";

    private FarmHelperCommandTree() {
    }

    public static <S> LiteralArgumentBuilder<S> root(
            String name,
            FarmHelperCommandService service,
            BiConsumer<S, String> feedback
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(feedback, "feedback");

        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.literal(name);
        root.executes(context -> emit(context.getSource(), service.status(), feedback));
        root.then(FarmHelperCommandTree.<S>literalNode("status")
                .executes(context -> emit(context.getSource(), service.status(), feedback)));
        root.then(FarmHelperCommandTree.<S>literalNode("toggle")
                .executes(context -> emit(context.getSource(), service.toggle(), feedback)));
        root.then(FarmHelperCommandTree.<S>literalNode("stop")
                .executes(context -> emit(context.getSource(), service.stop(), feedback)));
        root.then(FarmHelperCommandTree.<S>literalNode("reset")
                .executes(context -> emit(context.getSource(), service.reset(), feedback)));
        root.then(config(service, feedback));
        root.then(FarmHelperCommandTree.<S>literalNode("open")
                .executes(context -> emit(context.getSource(), service.openConfig(), feedback)));
        root.then(FarmHelperCommandTree.<S>literalNode("rotation")
                .then(FarmHelperCommandTree.<S>literalNode("test")
                        .then(RequiredArgumentBuilder.<S, Float>argument("yaw", floatArg(-180.0F, 180.0F))
                                .then(RequiredArgumentBuilder.<S, Float>argument("pitch", floatArg(-90.0F, 90.0F))
                                        .then(RequiredArgumentBuilder.<S, Integer>argument(
                                                        "duration_ms", integer(50, 5000))
                                                .executes(context -> emit(
                                                        context.getSource(),
                                                        service.testRotation(
                                                                getFloat(context, "yaw"),
                                                                getFloat(context, "pitch"),
                                                                getInteger(context, "duration_ms")),
                                                        feedback)))))));
        root.then(FarmHelperCommandTree.<S>literalNode("input")
                .then(FarmHelperCommandTree.<S>literalNode("release")
                        .executes(context -> emit(context.getSource(), service.releaseInput(), feedback))));
        root.then(FarmHelperCommandTree.<S>literalNode("macro")
                .then(FarmHelperCommandTree.<S>literalNode("start")
                        .executes(context -> emit(context.getSource(), service.startMacro(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("pause")
                        .executes(context -> emit(context.getSource(), service.pauseMacro(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("resume")
                        .executes(context -> emit(context.getSource(), service.resumeMacro(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("stop")
                        .executes(context -> emit(context.getSource(), service.stopMacro(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("mode")
                        .then(RequiredArgumentBuilder.<S, Integer>argument("mode", integer(0, 13))
                                .executes(context -> emit(context.getSource(),
                                        service.setMacroMode(getInteger(context, "mode")), feedback)))));
        root.then(FarmHelperCommandTree.<S>literalNode("spawn")
                .then(FarmHelperCommandTree.<S>literalNode("set")
                        .executes(context -> emit(context.getSource(), service.setSpawn(), feedback))));
        root.then(FarmHelperCommandTree.<S>literalNode("rewarp")
                .then(FarmHelperCommandTree.<S>literalNode("add")
                        .executes(context -> emit(context.getSource(), service.addRewarp(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("remove")
                        .executes(context -> emit(context.getSource(), service.removeRewarp(), feedback)))
                .then(FarmHelperCommandTree.<S>literalNode("clear")
                        .executes(context -> emit(context.getSource(), service.clearRewarps(), feedback))));
        root.then(FarmHelperCommandTree.<S>literalNode("diagnostics")
                .executes(context -> emit(context.getSource(), service.diagnostics(), feedback)));
        return root;
    }

    public static <S> LiteralArgumentBuilder<S> alias(
            String name,
            LiteralCommandNode<S> target,
            FarmHelperCommandService service,
            BiConsumer<S, String> feedback
    ) {
        return LiteralArgumentBuilder.<S>literal(name)
                .executes(context -> emit(context.getSource(), service.status(), feedback))
                .redirect(target);
    }

    private static <S> LiteralArgumentBuilder<S> config(
            FarmHelperCommandService service,
            BiConsumer<S, String> feedback
    ) {
        LiteralArgumentBuilder<S> config = literalNode("config");
        LiteralArgumentBuilder<S> get = literalNode("get");
        get.executes(context -> emitConfig(context.getSource(), service, feedback));
        LiteralArgumentBuilder<S> set = literalNode("set");
        LiteralArgumentBuilder<S> reset = literalNode("reset");
        reset.executes(context -> emit(context.getSource(), service.resetConfig(), feedback));

        for (FarmHelperConfigKey key : FarmHelperConfigKey.values()) {
            get.then(FarmHelperCommandTree.<S>literalNode(key.commandName()).executes(context -> {
                feedback.accept(context.getSource(), formatConfig(key, service.configValue(key)));
                return Command.SINGLE_SUCCESS;
            }));

            RequiredArgumentBuilder<S, Float> value = RequiredArgumentBuilder.argument(
                    VALUE_ARGUMENT,
                    floatArg(key.minimum(), key.maximum()));
            value.executes(context -> emit(
                    context.getSource(),
                    service.setConfig(key, getFloat(context, VALUE_ARGUMENT)),
                    feedback));
            set.then(FarmHelperCommandTree.<S>literalNode(key.commandName()).then(value));

            reset.then(FarmHelperCommandTree.<S>literalNode(key.commandName())
                    .executes(context -> emit(context.getSource(), service.resetConfig(key), feedback)));
        }

        config.then(get);
        config.then(set);
        config.then(reset);
        config.then(FarmHelperCommandTree.<S>literalNode("open")
                .executes(context -> emit(context.getSource(), service.openConfig(), feedback)));
        return config;
    }

    private static <S> LiteralArgumentBuilder<S> literalNode(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S> int emitConfig(
            S source,
            FarmHelperCommandService service,
            BiConsumer<S, String> feedback
    ) {
        for (FarmHelperConfigKey key : FarmHelperConfigKey.values()) {
            feedback.accept(source, formatConfig(key, service.configValue(key)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String formatConfig(FarmHelperConfigKey key, float value) {
        return key.commandName() + " = " + value;
    }

    private static <S> int emit(S source, List<String> messages, BiConsumer<S, String> feedback) {
        Objects.requireNonNull(messages, "messages");
        messages.forEach(message -> feedback.accept(source, message));
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int emit(S source, CommandActionResult result, BiConsumer<S, String> feedback) {
        feedback.accept(source, result.message());
        return result.successful() ? Command.SINGLE_SUCCESS : 0;
    }
}
