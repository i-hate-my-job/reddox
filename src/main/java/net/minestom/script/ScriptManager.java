package net.minestom.script;

import net.minestom.script.command.EntityCommand;
import net.minestom.script.command.FunctionCommand;
import net.minestom.script.command.WorldCommand;
import net.minestom.script.handler.ScriptAPI;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.entity.Player;
import org.apache.commons.io.FilenameUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class ScriptManager {

    public static final ScriptAPI API = new ScriptAPI();

    public static final String SCRIPT_FOLDER = "scripts";

    public static final Executor EXECUTOR = new Executor();

    private static final Context CONTEXT = Context.newBuilder("js", "python")
            .allowHostAccess(HostAccess.ALL).build();
    private static final List<Script> SCRIPTS = new CopyOnWriteArrayList<>();

    private static final Map<String, String> EXTENSION_MAP = Map.of(
            "js", "js",
            "py", "python");

    private static volatile boolean loaded;

    private static Function<Player, Boolean> commandPermission = player -> true;

    /**
     * Loads and evaluate all scripts in the folder {@link #SCRIPT_FOLDER}.
     */
    public static void load() {
        if (loaded) {
            System.err.println("The script manager is already loaded!");
            return;
        }
        loaded = true;

        // Init events for signals
        {
            EventSignal.init(MinecraftServer.getGlobalEventHandler());
        }

        // Load commands
        {
            CommandManager commandManager = MinecraftServer.getCommandManager();
            commandManager.register(new FunctionCommand());
            commandManager.register(new WorldCommand());
            commandManager.register(new EntityCommand());
        }


        final File scriptFolder = new File(SCRIPT_FOLDER);

        if (!scriptFolder.exists()) {
            return; // No script folder
        }

        // JS bindings
        {
            Value bindings = CONTEXT.getBindings("js");
            bindings.putMember("executor", EXECUTOR);
        }

        // Python bindings
        {
            Value bindings = CONTEXT.getBindings("python");
            bindings.putMember("executor", EXECUTOR);
        }

        final File[] folderFiles = scriptFolder.listFiles();
        if (folderFiles == null) {
            System.err.println(scriptFolder + " is not a folder!");
            return;
        }

        for (File file : folderFiles) {
            try {
                final String extension = FilenameUtils.getExtension(file.getName());
                final String fileString = Files.readString(file.toPath());

                final String language = EXTENSION_MAP.get(extension);
                if (language == null) {
                    // Invalid file extension
                    System.err.println("Invalid file extension for " + file + ", ignored");
                    continue;
                }

                Source source = Source.create(language, fileString);
                Script script = new Script(source);

                // Evaluate the script (start registering listeners)
                script.eval(CONTEXT);

                SCRIPTS.add(script);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void shutdown() {
        CONTEXT.close();
    }

    /**
     * Gets all the evaluated python scripts.
     *
     * @return a list containing the scripts
     */
    @NotNull
    public static List<Script> getScripts() {
        return SCRIPTS;
    }

    @NotNull
    public static Function<Player, Boolean> getCommandPermission() {
        return commandPermission;
    }

    public static void setCommandPermission(@NotNull Function<Player, Boolean> commandPermission) {
        ScriptManager.commandPermission = commandPermission;
    }
}
