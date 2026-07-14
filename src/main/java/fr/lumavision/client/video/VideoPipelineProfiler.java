package fr.lumavision.client.video;

import fr.lumavision.LumaVisionMod;
import fr.lumavision.config.ModConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated client-side timings for the dynamic video pipeline.
 */
@OnlyIn(Dist.CLIENT)
public final class VideoPipelineProfiler {

    private static final AtomicLong ndiConversions = new AtomicLong();
    private static final AtomicLong ndiConversionNanos = new AtomicLong();
    private static final AtomicLong textureUploads = new AtomicLong();
    private static final AtomicLong textureUploadNanos = new AtomicLong();
    private static final AtomicLong colorGrades = new AtomicLong();
    private static final AtomicLong colorGradeNanos = new AtomicLong();
    private static final AtomicLong frameHashes = new AtomicLong();
    private static final AtomicLong frameHashNanos = new AtomicLong();
    private static final AtomicLong skippedUploadTicks = new AtomicLong();
    private static final AtomicLong skippedDuplicateFrames = new AtomicLong();

    private static long lastReportMs = System.currentTimeMillis();

    private VideoPipelineProfiler() {
    }

    public static boolean enabled() {
        return ModConfig.ENABLE_VIDEO_PROFILING.get();
    }

    public static void recordNdiConversion(long nanos) {
        if (!enabled()) {
            return;
        }
        ndiConversions.incrementAndGet();
        ndiConversionNanos.addAndGet(nanos);
    }

    public static void recordTextureUpload(long nanos) {
        if (!enabled()) {
            return;
        }
        textureUploads.incrementAndGet();
        textureUploadNanos.addAndGet(nanos);
    }

    public static void recordColorGrade(long nanos) {
        if (!enabled()) {
            return;
        }
        colorGrades.incrementAndGet();
        colorGradeNanos.addAndGet(nanos);
    }

    public static void recordFrameHash(long nanos) {
        if (!enabled()) {
            return;
        }
        frameHashes.incrementAndGet();
        frameHashNanos.addAndGet(nanos);
    }

    public static void recordSkippedUploadTick() {
        if (enabled()) {
            skippedUploadTicks.incrementAndGet();
        }
    }

    public static void recordSkippedDuplicateFrame() {
        if (enabled()) {
            skippedDuplicateFrames.incrementAndGet();
        }
    }

    public static void reportIfDue(int screenPipelines, int sharedTextures, int sharedTextureReferences) {
        if (!enabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastReportMs < ModConfig.VIDEO_PROFILING_INTERVAL_MS.get()) {
            return;
        }
        lastReportMs = nowMs;

        long ndiCount = ndiConversions.getAndSet(0);
        long ndiNanos = ndiConversionNanos.getAndSet(0);
        long uploadCount = textureUploads.getAndSet(0);
        long uploadNanos = textureUploadNanos.getAndSet(0);
        long gradeCount = colorGrades.getAndSet(0);
        long gradeNanos = colorGradeNanos.getAndSet(0);
        long hashCount = frameHashes.getAndSet(0);
        long hashNanos = frameHashNanos.getAndSet(0);
        long skippedTicks = skippedUploadTicks.getAndSet(0);
        long skippedDuplicates = skippedDuplicateFrames.getAndSet(0);

        LumaVisionMod.LOGGER.info(
                "LumaVision video profile: screens={}, sharedTextures={}, sharedRefs={}, ndi={} avg={}ms, uploads={} avg={}ms, grading={} avg={}ms, hash={} avg={}ms, skippedTicks={}, skippedDuplicates={}",
                screenPipelines,
                sharedTextures,
                sharedTextureReferences,
                ndiCount,
                averageMs(ndiNanos, ndiCount),
                uploadCount,
                averageMs(uploadNanos, uploadCount),
                gradeCount,
                averageMs(gradeNanos, gradeCount),
                hashCount,
                averageMs(hashNanos, hashCount),
                skippedTicks,
                skippedDuplicates
        );
    }

    private static String averageMs(long nanos, long count) {
        if (count <= 0) {
            return "0.000";
        }
        return String.format("%.3f", nanos / 1_000_000.0D / count);
    }
}
