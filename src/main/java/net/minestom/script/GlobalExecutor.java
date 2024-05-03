package net.minestom.script;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.script.command.RichCommand;
import net.minestom.script.property.PlayerProperty;
import net.minestom.script.property.Properties;
import net.minestom.script.utils.CommandUtils;
import net.minestom.script.utils.ExceptionUtils;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.utils.entity.EntityFinder;
import net.minestom.server.utils.validate.Check;
import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Layer between the server and the scripts.
 * <p>
 * Responsible for all interactions with Minecraft.
 */
public class GlobalExecutor implements Executor {

    private final static List<GlobalExecutor> GLOBAL_EXECUTORS = new CopyOnWriteArrayList<>();

    /**
     * Returns all the signals ever registered.
     * <p>
     * Instantiates a new {@link Set} when called, caching the result is recommended.
     *
     * @return the registered signals name
     */
    public static @NotNull Set<String> retrieveSignals() {
        Set<String> signals = new HashSet<>();
        for (GlobalExecutor executor : GLOBAL_EXECUTORS) {
            signals.addAll(executor.signalMap.keySet());
        }
        return Collections.unmodifiableSet(signals);
    }

    private final Map<String, List<SignalCallback>> signalMap = new ConcurrentHashMap<>();
    private final Map<String, Command> commandMap = new ConcurrentHashMap<>();

    protected volatile Script script;

    @Override
    public ProxyObject run(@NotNull Object... inputs) {
        final String command = inputToString(inputs);
        final CommandResult result = MinecraftServer.getCommandManager().executeServerCommand(command);
        return CommandUtils.retrieveCommandData(result, command);
    }

    public @Nullable ProxyObject runAs(@NotNull Value playerValue, @NotNull Object... inputs) {
        Check.argCondition(!playerValue.isProxyObject(), "#runAs requires a player!");
        {
            ProxyObject proxyObject = playerValue.asProxyObject();
            Check.argCondition(!(proxyObject instanceof PlayerProperty), "#runAs requires a player!");
        }
        final PlayerProperty playerProperty = playerValue.asProxyObject();

        final UUID uuid = UUID.fromString(((Value) playerProperty.getMember("uuid")).asString());
        final Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
        if (player == null)
            return null;

        final String command = inputToString(inputs);
        final CommandResult result = MinecraftServer.getCommandManager().execute(player, command);
        return CommandUtils.retrieveCommandData(result, command);
    }

    public @NotNull CommandFunction make(@NotNull String string, @NotNull ProxyObjectMapper mapper) {
        return args -> {
            final String input = MessageFormat.format(string, args);
            try {
                if (script != null) {
                    script.enter();
                }
                return mapper.map(run(input));
            } catch (Throwable e) {
                MinecraftServer.getExceptionManager().handleException(e);
                return Value.asValue(null);
            } finally {
                if (script != null) {
                    script.leave();
                }
            }
        };
    }

    public Executor with(@NotNull ExecutionOptions options) {
        throw new UnsupportedOperationException("TODO #with(options)");
    }

    public void onSignal(@NotNull String signal, @NotNull SignalCallback callback) {
        var listeners = signalMap.computeIfAbsent(signal.toLowerCase(), s -> new CopyOnWriteArrayList<>());
        listeners.add((properties, output) ->
                accessScript(script, () -> callback.accept(properties, output)));
    }

    public @NotNull ProxyObject signal(@NotNull String signal, @NotNull Properties properties) {
        ProxyObject result = ProxyObject.fromMap(new HashMap<>());
        for (GlobalExecutor globalExecutor : GLOBAL_EXECUTORS) {
            List<SignalCallback> listeners = globalExecutor.signalMap.get(signal.toLowerCase());
            if (listeners != null && !listeners.isEmpty()) {
                for (SignalCallback callback : listeners) {
                    // Callback should be thread-safe
                    callback.accept(properties, result);
                }
            }
        }
        return result;
    }

    public synchronized void registerCommand(@NotNull String format, @NotNull CommandCallback callback) {
        String commandName;
        if (format.contains(StringUtils.SPACE)) {
            final int index = format.indexOf(StringUtils.SPACE);
            commandName = format.substring(0, index);
            format = format.substring(index + 1);
        } else {
            // No argument
            commandName = format;
            format = "";
        }

        // Create command
        Command command = new RichCommand(commandName);
        command.addSyntax((sender, context) -> {
            if (!sender.isPlayer()) {
                // TODO console support
                ExceptionUtils.sendMessage(
                        Component.text("Currently only players can use script commands",
                                NamedTextColor.RED));
                return;
            }
            PlayerProperty playerProperty = new PlayerProperty(sender.asPlayer());
            Properties properties = new Properties();
            for (var entry : context.getMap().entrySet()) {
                final String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof EntityFinder) {
                    // Retrieve entities based on the sender
                    value = ((EntityFinder) value).find(sender);
                }
                properties.putMember(key, value);
            }

            accessScript(script, () -> callback.accept(playerProperty, properties));
        }, ArgumentType.generate(format));

        this.commandMap.put(commandName, command);
        MinecraftServer.getCommandManager().register(command);
        CommandUtils.updateCommands();
    }

    protected synchronized void register() {
        GLOBAL_EXECUTORS.add(this);
    }

    protected synchronized void unregister() {
        // Clear signals
        this.signalMap.clear();

        // Clear registered commands
        final boolean hasCommand = !commandMap.isEmpty();
        if (hasCommand) {
            this.commandMap.forEach((s, command) ->
                    MinecraftServer.getCommandManager().unregister(command));
            this.commandMap.clear();
            CommandUtils.updateCommands();
        }

        GLOBAL_EXECUTORS.remove(this);
    }

    private static void accessScript(@Nullable Script script, @NotNull Runnable runnable) {
        try {
            if (script != null) {
                script.enter();
            }
            runnable.run();
        } catch (Throwable e) {
            MinecraftServer.getExceptionManager().handleException(e);
        } finally {
            if (script != null) {
                script.leave();
            }
        }
    }

    private static String inputToString(Object... inputs) {
        return Arrays.stream(inputs)
                .map(Object::toString)
                .collect(Collectors.joining(StringUtils.SPACE));
    }

}
