package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WorldListWidget.class)
public interface WorldListWidgetInvoker {
	@Invoker("load")
	void airicraft$load();
}
