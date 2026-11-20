package org.infotoast.dropsoverhaul.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Native command feedback using Minecraft 1.20's supplier API. */
final class CommandFeedback {
    private CommandFeedback() { }
    static void send(CommandSourceStack source, Component message) { source.sendSuccess(() -> message, false); }
}
