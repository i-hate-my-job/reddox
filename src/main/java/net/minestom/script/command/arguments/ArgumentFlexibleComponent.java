package net.minestom.script.command.arguments;

import com.google.gson.stream.JsonReader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentString;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import org.jetbrains.annotations.NotNull;

import java.io.StringReader;

/**
 * Component argument accepting JSON or MiniMessage input.
 */
public class ArgumentFlexibleComponent extends Argument<Component> {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final boolean infinite;

    public ArgumentFlexibleComponent(@NotNull String id, boolean infinite) {
        super(id, true, infinite);
        this.infinite = infinite;
    }

    @Override
    public @NotNull Component parse(@NotNull CommandSender sender, @NotNull String input) throws ArgumentSyntaxException {
        try {
            final JsonReader reader = new JsonReader(new StringReader(input));

            return GsonComponentSerializer.gson()
                .serializer()
                .getAdapter(Component.class)
                .read(reader);
        } catch(Exception ex) {
            if(!infinite) {
                // Input needs to be quoted
                input = Argument.parse(sender, new ArgumentString(input));
            }

            // Otherwise, parse with MiniMessage
            return MINI_MESSAGE.deserialize(input);
        }
    }

    @Override
    public String parser() {
        // using minecraft:function is a namespace that doesn't break anything
        // and still allows auto-complete to work fine, no errors seem to occur with this
        // so eh, it's good enough.
        return "minecraft:function";
    }
}
