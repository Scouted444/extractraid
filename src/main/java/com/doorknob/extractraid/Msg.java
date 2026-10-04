package com.doorknob.extractraid;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Small helper for MiniMessage-formatted chat, action bar and titles. */
final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Extract</gold>]</dark_gray> <gray>";

    private Msg() {}

    static void send(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(PREFIX + mini));
    }

    static void bar(Player p, String mini) {
        p.sendActionBar(MM.deserialize(mini));
    }

    static void title(Player p, String title, String subtitle) {
        p.showTitle(Title.title(MM.deserialize(title), MM.deserialize(subtitle)));
    }
}
