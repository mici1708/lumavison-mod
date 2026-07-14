package fr.lumavision.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import fr.lumavision.LumaVisionMod;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = LumaVisionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LumaVisionShaders {

    private static ShaderInstance screenShader;

    private LumaVisionShaders() {
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                new ResourceLocation(LumaVisionMod.MOD_ID, "lumavision_screen"),
                DefaultVertexFormat.NEW_ENTITY
        ), shader -> screenShader = shader);
    }

    public static ShaderInstance screenShader() {
        return screenShader;
    }
}
