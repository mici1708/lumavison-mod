package fr.lumavision.client.display;

import fr.lumavision.screen.ScreenDisplaySettings;
import fr.lumavision.video.VideoFrame;

/**
 * Applies brightness, contrast, gamma, and color temperature to a frame copy.
 * Runs in the display layer — does not modify {@link fr.lumavision.video.VideoSource}.
 */
public final class DisplayColorGrading {

    private DisplayColorGrading() {
    }

    public static void applyInto(VideoFrame source, VideoFrame target, ScreenDisplaySettings settings) {
        LookupTables tables = new LookupTables();
        tables.update(settings);
        applyInto(source, target, tables);
    }

    public static void applyInto(VideoFrame source, VideoFrame target, LookupTables tables) {
        if (source.getWidth() != target.getWidth() || source.getHeight() != target.getHeight()) {
            throw new IllegalArgumentException("Frame size mismatch");
        }

        target.copyColorGradedFrom(source, tables.redMap, tables.greenMap, tables.blueMap);
    }

    public static int[] vertexColor(ScreenDisplaySettings settings) {
        float dimming = Math.min(1.0F, settings.brightness());
        float colorTemp = settings.colorTemp();
        float red = Math.min(1.0F, dimming * (1.0F + colorTemp * 0.25F));
        float green = dimming;
        float blue = Math.min(1.0F, dimming * (1.0F - colorTemp * 0.25F));
        return new int[]{
                toByte(red),
                toByte(green),
                toByte(blue),
                255
        };
    }

    public static final class LookupTables {
        private final int[] redMap = new int[256];
        private final int[] greenMap = new int[256];
        private final int[] blueMap = new int[256];
        private String cacheKey;

        public void update(ScreenDisplaySettings settings) {
            String newCacheKey = settings.textureColorGradingKey();
            if (newCacheKey.equals(cacheKey)) {
                return;
            }
            cacheKey = newCacheKey;
            buildLookupTables(settings, redMap, greenMap, blueMap);
        }
    }

    private static void buildLookupTables(ScreenDisplaySettings settings, int[] redMap, int[] greenMap, int[] blueMap) {
        float brightness = Math.max(1.0F, settings.brightness());
        float contrast = settings.contrast();
        float gamma = settings.gamma();
        float invGamma = 1.0F / gamma;

        for (int i = 0; i < 256; i++) {
            float channel = i / 255.0F;
            redMap[i] = toByte((float) Math.pow(Math.max(0.0F,
                    applyContrast(channel, contrast) * brightness), invGamma));
            greenMap[i] = toByte((float) Math.pow(Math.max(0.0F,
                    applyContrast(channel, contrast) * brightness), invGamma));
            blueMap[i] = toByte((float) Math.pow(Math.max(0.0F,
                    applyContrast(channel, contrast) * brightness), invGamma));
        }
    }

    private static float applyContrast(float channel, float contrast) {
        return (channel - 0.5F) * contrast + 0.5F;
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }
}
