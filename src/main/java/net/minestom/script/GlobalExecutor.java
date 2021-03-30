package net.minestom.script;

import net.minestom.script.command.RichCommand;
import net.minestom.script.property.PlayerProperty;
import net.minestom.script.property.Properties;
import net.minestom.script.utils.CommandUtils;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.command.builder.arguments.ArgumentType;
import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jetbrains.annotations.NotNull;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final Map<String, List<SignalCallback>> signalMap = new ConcurrentHashMap<>();
    private final Map<String, Command> commandMap = new ConcurrentHashMap<>();

    @Override
    public ProxyObject run(@NotNull Object... inputs) {
        final String command = inputToString(inputs);
        final CommandResult result = MinecraftServer.getCommandManager().executeServerCommand(command);
        return CommandUtils.retrieveCommandData(result, command);
    }

    @NotNull
    public CommandFunction make(@NotNull String string, @NotNull ProxyObjectMapper mapper) {
        return args -> {
            final String input = MessageFormat.format(string, args);
            return mapper.map(run(input));
        };
    }

    public Executor with(@NotNull ExecutionOptions options) {
        throw new UnsupportedOperationException("TODO #with(options)");
    }

    public void onSignal(@NotNull String signal, @NotNull SignalCallback callback) {
        List<SignalCallback> listeners =
                signalMap.computeIfAbsent(signal.toLowerCase(), s -> new CopyOnWriteArrayList<>());
        listeners.add(callback);
    }

    @NotNull
    public ProxyObject signal(@NotNull String signal, @NotNull Properties properties) {
        ProxyObject result = ProxyObject.fromMap(new HashMap<>());
        for (GlobalExecutor globalExecutor : GLOBAL_EXECUTORS) {
            List<SignalCallback> listeners = globalExecutor.signalMap.get(signal.toLowerCase());
            if (listeners != null && !listeners.isEmpty()) {
                for (SignalCallback callback : listeners) {
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
                System.err.println("Currently only players can use script commands");
                return;
            }
            PlayerProperty playerProperty = new PlayerProperty(sender.asPlayer());
            Properties properties = new Properties();
            context.getMap().forEach(properties::putMember);

            callback.accept(playerProperty, properties);
        }, ArgumentType.generate(format));

        this.commandMap.put(commandName, command);
        MinecraftServer.getCommandManager().register(command);
        CommandUtils.updateCommands();
    }

    protected void register() {
        GLOBAL_EXECUTORS.add(this);
    }

    protected synchronized void unregister() {
        this.signalMap.clear();

        final boolean hasCommand = !commandMap.isEmpty();
        if (hasCommand) {
            this.commandMap.forEach((s, command) ->
                    MinecraftServer.getCommandManager().unregister(command));
            this.commandMap.clear();
            CommandUtils.updateCommands();
        }

        GLOBAL_EXECUTORS.remove(this);
    }

    private static String inputToString(Object... inputs) {
        return Arrays.stream(inputs)
                .map(Object::toString)
                .collect(Collectors.joining(StringUtils.SPACE));
    }

}