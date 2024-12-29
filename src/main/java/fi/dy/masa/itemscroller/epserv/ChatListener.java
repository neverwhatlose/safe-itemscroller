package fi.dy.masa.itemscroller.epserv;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ChatListener {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager
                    .literal("trust")
                    .executes(ctx -> {
                        handle(ctx);
                        return 0;
                    })
            );
        }));
    }

    private static void handle(CommandContext<FabricClientCommandSource> context) {
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.OPEN_URL, "https://github.com/neverwhatlose/safe-itemscroller");
        context.getSource().sendFeedback(Text
                .literal("Safe version of ItemScroller mod for EP by neverwhatlose, click to get repository")
                .setStyle(Text.empty().getStyle().withClickEvent(clickEvent))
                .formatted(Formatting.DARK_GREEN)
        );
    }
}
