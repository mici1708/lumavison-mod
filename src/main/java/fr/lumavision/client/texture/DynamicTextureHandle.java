package fr.lumavision.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import fr.lumavision.LumaVisionMod;
import fr.lumavision.client.display.DisplayColorGrading;
import fr.lumavision.client.video.VideoPipelineProfiler;
import fr.lumavision.video.VideoFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

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
        long startNanos = VideoPipelineProfiler.enabled() ? System.nanoTime() : 0L;
        try {
            DynamicTexture uploadTexture = prepareUploadTexture(frame);
            if (uploadTexture == null) {
                return;
            }

            NativeImage pixels = uploadTexture.getPixels();
            if (pixels == null) {
                return;
            }

            frame.writeTo(pixels);
            uploadTexture.upload();
            activeTextureIndex = 1 - activeTextureIndex;
        } finally {
            if (startNanos != 0L) {
                VideoPipelineProfiler.recordTextureUpload(System.nanoTime() - startNanos);
            }
        }
    }

    public void uploadColorGraded(VideoFrame frame, DisplayColorGrading.LookupTables tables) {
        long startNanos = VideoPipelineProfiler.enabled() ? System.nanoTime() : 0L;
        try {
            DynamicTexture uploadTexture = prepareUploadTexture(frame);
            if (uploadTexture == null) {
                return;
            }

            NativeImage pixels = uploadTexture.getPixels();
            if (pixels == null) {
                return;
            }

            long gradeStartNanos = VideoPipelineProfiler.enabled() ? System.nanoTime() : 0L;
            frame.writeColorGradedTo(pixels, tables.redMap(), tables.greenMap(), tables.blueMap());
            if (gradeStartNanos != 0L) {
                VideoPipelineProfiler.recordColorGrade(System.nanoTime() - gradeStartNanos);
            }
            uploadTexture.upload();
            activeTextureIndex = 1 - activeTextureIndex;
        } finally {
            if (startNanos != 0L) {
                VideoPipelineProfiler.recordTextureUpload(System.nanoTime() - startNanos);
            }
        }
    }

    private DynamicTexture prepareUploadTexture(VideoFrame frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        if (uploadedWidth != width || uploadedHeight != height) {
            recreateTexture(width, height);
        }

        int uploadIndex = 1 - activeTextureIndex;
        DynamicTexture uploadTexture = textures[uploadIndex];
        NativeImage pixels = uploadTexture.getPixels();
        if (pixels == null
                || pixels.getWidth() != width
                || pixels.getHeight() != height) {
            return null;
        }
        return uploadTexture;
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
        };
        if (minecraft.isSameThread()) {
            cleanup.run();
        } else {
            minecraft.execute(cleanup);
        }
    }
}
