package com.ethan.emicraftingchains.crafting;

import net.minecraft.network.chat.Component;

public final class PlanFailure extends Exception {
    private final Component messageComponent;

    public PlanFailure(Component messageComponent) {
        super(messageComponent.getString());
        this.messageComponent = messageComponent;
    }

    public Component messageComponent() {
        return messageComponent;
    }
}
