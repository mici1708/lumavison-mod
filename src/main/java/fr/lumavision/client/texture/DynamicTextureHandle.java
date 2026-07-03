package fr.lumavision.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import fr.lumavision.LumaVisionMod;
import fr.lumavision.video.VideoFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * GPU-backed texture updated from {@link VideoFrame} data.
 * <p>
 * This is the last step before rendering — {@link fr.lumavision.client.render.ScreenRenderer}
 * only binds the returned {@link ResourceLocation}.
 */
public final class DynamicTextureHandle implements AutoCloseable {

    private final DynamicTexture[] textures = new DynamicTexture[2];
    private final ResourceLocation[] locations = new ResourceLocation[2];
    private int activeTextureIndex;
    private int uploadedWidth;
    private int uploadedHeight;
    private ByteBuffer uploadBuffer;

    public DynamicTextureHandle(String textureId) {
        this.locations[0] = new ResourceLocation(LumaVisionMod.MOD_ID, "dynamic/" + textureId + "_0");
        this.locations[1] = new ResourceLocation(LumaVisionMod.MOD_ID, "dynamic/" + textureId + "_1");
        this.textures[0] = createTexture(1, 1);
        this.textures[1] = createTexture(1, 1);
        this.uploadedWidth = 1;
        this.uploadedHeight = 1;
        register(0);
        register(1);
    }

    public ResourceLocation location() {
        return locations[activeTextureIndex];
    }

    public void upload(VideoFrame frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        if (uploadedWidth != width || uploadedHeight != height) {
            recreateTexture(width, height);
        }

        int uploadIndex = 1 - activeTextureIndex;
        DynamicTexture uploadTexture = textures[uploadIndex];
        ensureUploadBuffer(width, height);
        frame.writeTo(uploadBuffer);
        uploadTexture.bind();
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GL11C.glTexSubImage2D(
                GL11C.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GL11C.GL_RGBA,
                GL11C.GL_UNSIGNED_BYTE,
                uploadBuffer
        );
        activeTextureIndex = uploadIndex;
    }

    private void ensureUploadBuffer(int width, int height) {
        int requiredBytes = width * height * Integer.BYTES;
        if (uploadBuffer != null && uploadBuffer.capacity() >= requiredBytes) {
            return;
        }
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
        }
        uploadBuffer = MemoryUtil.memAlloc(requiredBytes);
    }

    private void recreateTexture(int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int i = 0; i < textures.length; i++) {
            minecraft.getTextureManager().release(locations[i]);
            if (textures[i] != null) {
                textures[i].close();
            }
            textures[i] = createTexture(width, height);
            register(i);
        }
        activeTextureIndex = 0;
        uploadedWidth = width;
        uploadedHeight = height;
    }

    private void register(int index) {
        Minecraft.getInstance().getTextureManager().register(locations[index], textures[index]);
    }

    private static DynamicTexture createTexture(int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        image.fillRect(0, 0, width, height, 0xFF000000);
        return new DynamicTexture(image);
    }

    @Override
    public void close() {
        Minecraft minecraft = Minecraft.getInstance();
        Runnable cleanup = () -> {
            for (int i = 0; i < textures.length; i++) {
                minecraft.getTextureManager().release(locations[i]);
                textures[i].close();
            }
            if (uploadBuffer != null) {
                MemoryUtil.memFree(uploadBuffer);
                uploadBuffer = null;
            }
        };
        if (minecraft.isSameThread()) {
            cleanup.run();
        } else {
            minecraft.execute(cleanup);
        }
    }
}
