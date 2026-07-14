package fr.lumavision.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import fr.lumavision.LumaVisionMod;
import fr.lumavision.video.VideoFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * GPU-backed texture updated from {@link VideoFrame} data.
 * <p>
 * This is the last step before rendering — {@link fr.lumavision.client.render.ScreenRenderer}
 * only binds the returned {@link ResourceLocation}.
 */
public final class DynamicTextureHandle implements AutoCloseable {

    private final DynamicTexture[] textures = new DynamicTexture[2];
    private final ResourceLocation[] locations = new ResourceLocation[2];
    private final boolean[] textureStorageReady = new boolean[2];
    private PboTextureUploader uploader;
    private boolean pboUploadFailed;
    private int activeTextureIndex;
    private int uploadedWidth;
    private int uploadedHeight;

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
        if (uploadWithPbo(uploadIndex, uploadTexture, frame)) {
            activeTextureIndex = uploadIndex;
            return;
        }

        NativeImage pixels = uploadTexture.getPixels();
        if (pixels == null
                || pixels.getWidth() != width
                || pixels.getHeight() != height) {
            return;
        }

        frame.writeTo(pixels);
        uploadTexture.upload();
        textureStorageReady[uploadIndex] = true;
        activeTextureIndex = uploadIndex;
    }

    private boolean uploadWithPbo(int textureIndex, DynamicTexture texture, VideoFrame frame) {
        if (pboUploadFailed) {
            return false;
        }
        try {
            if (!textureStorageReady[textureIndex]) {
                texture.upload();
                textureStorageReady[textureIndex] = true;
            }
            if (uploader == null) {
                uploader = new PboTextureUploader();
            }
            int textureId = texture.getId();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            uploader.upload(textureId, frame);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            pboUploadFailed = true;
            return false;
        }
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
            textureStorageReady[i] = false;
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
            if (uploader != null) {
                uploader.close();
                uploader = null;
            }
            for (int i = 0; i < textures.length; i++) {
                minecraft.getTextureManager().release(locations[i]);
                textures[i].close();
            }
        };
        if (minecraft.isSameThread()) {
            cleanup.run();
        } else {
            minecraft.execute(cleanup);
        }
    }
}
