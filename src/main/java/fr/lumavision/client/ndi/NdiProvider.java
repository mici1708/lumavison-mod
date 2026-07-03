package fr.lumavision.client.ndi;

import fr.lumavision.LumaVisionMod;
import fr.lumavision.client.video.TestPatternVideoSource;
import fr.lumavision.config.ModConfig;
import fr.lumavision.video.VideoFrame;
import fr.lumavision.video.VideoSource;
import fr.lumavision.video.VideoSourceDescriptor;
import fr.lumavision.video.VideoSourceType;
import fr.lumavision.video.provider.CatalogSourceEntry;
import fr.lumavision.video.provider.ProviderConfigOption;
import fr.lumavision.video.provider.ProviderConfigOptionType;
import fr.lumavision.video.provider.VideoSourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NDI media provider backed by Devolay discovery and receivers.
 */
@OnlyIn(Dist.CLIENT)
public final class NdiProvider implements VideoSourceProvider {

    public static final NdiProvider INSTANCE = new NdiProvider();

    private final Map<String, SharedNdiSource> sharedSources = new HashMap<>();

    private NdiProvider() {
    }

    @Override
    public VideoSourceType type() {
        return VideoSourceType.NDI;
    }

    @Override
    public String providerId() {
        return "ndi";
    }

    @Override
    public String displayName() {
        return "NDI";
    }

    @Override
    public String sourceIdPrefix() {
        return VideoSourceDescriptor.NDI_PREFIX;
    }

    @Override
    public boolean isEnabled() {
        return ModConfig.ENABLE_NDI.get();
    }

    /**
     * Whether the NDI native runtime loaded successfully (independent of the enableNdi config flag).
     */
    @Override
    public boolean isAvailable() {
        return NdiRuntime.init();
    }

    @Override
    @Nullable
    public String unavailableReason() {
        if (isAvailable()) {
            return null;
        }
        return NdiRuntime.getFailureReason();
    }

    @Override
    public boolean isImplemented() {
        return true;
    }

    @Override
    public void start() {
        ensureRuntime();
        if (isEnabled()) {
            NdiDiscoveryService.getInstance().start();
        }
    }

    @Override
    public void stop() {
        NdiDiscoveryService.getInstance().shutdown();
        clearSharedSources();
    }

    @Override
    public void refreshSources() {
        ensureRuntime();
        if (isEnabled()) {
            NdiDiscoveryService.getInstance().start();
        }
    }

    /**
     * Called after config reload or client bootstrap to align discovery with {@link ModConfig#ENABLE_NDI}.
     */
    public static void applyConfig() {
        ensureRuntime();
        if (ModConfig.ENABLE_NDI.get()) {
            NdiDiscoveryService.getInstance().start();
        } else {
            NdiDiscoveryService.getInstance().shutdown();
        }
    }

    private static void ensureRuntime() {
        if (!NdiRuntime.init() && NdiRuntime.getFailureReason() != null) {
            LumaVisionMod.LOGGER.debug("NDI unavailable: {}", NdiRuntime.getFailureReason());
        }
    }

    @Override
    public boolean supports(VideoSourceDescriptor descriptor) {
        return descriptor.isNdi();
    }

    @Override
    public VideoSourceDescriptor descriptorFromPayload(String payload) {
        return VideoSourceDescriptor.ndi(payload);
    }

    @Override
    public List<CatalogSourceEntry> listSources() {
        if (!isEnabled() || !isAvailable()) {
            return List.of();
        }

        ensureRuntime();
        NdiDiscoveryService.getInstance().start();

        List<CatalogSourceEntry> entries = new ArrayList<>();
        for (NdiSourceInfo source : NdiDiscoveryService.getInstance().getDiscoveredSources()) {
            VideoSourceDescriptor descriptor = VideoSourceDescriptor.ndi(source.sourceName());
            entries.add(new CatalogSourceEntry(
                    providerId(),
                    descriptor,
                    source.sourceName(),
                    "NDI network source",
                    true
            ));
        }
        return List.copyOf(entries);
    }

    @Override
    @Nullable
    public VideoSourceDescriptor defaultDescriptor() {
        if (!isEnabled() || !isAvailable()) {
            return null;
        }

        String defaultSource = ModConfig.NDI_DEFAULT_SOURCE.get();
        if (defaultSource != null && !defaultSource.isBlank()) {
            return VideoSourceDescriptor.ndi(defaultSource.trim());
        }

        if (ModConfig.NDI_AUTO_SELECT_FIRST.get()) {
            String first = NdiDiscoveryService.getInstance().getFirstSourceName();
            if (first != null && !first.isBlank()) {
                return VideoSourceDescriptor.ndi(first);
            }
        }

        return null;
    }

    @Override
    public VideoSource create(VideoSourceDescriptor descriptor, int targetWidth, int targetHeight) {
        if (!supports(descriptor) || !isEnabled() || !isAvailable()) {
            throw new IllegalStateException("NDI provider cannot create source for " + descriptor.cacheKey());
        }

        try {
            return acquireSharedSource(descriptor.payload(), targetWidth, targetHeight);
        } catch (Throwable throwable) {
            LumaVisionMod.LOGGER.warn(
                    "NDI source '{}' unavailable, using test pattern",
                    descriptor.payload(),
                    throwable
            );
            return new TestPatternVideoSource(targetWidth, targetHeight);
        }
    }

    private synchronized VideoSource acquireSharedSource(String sourceName, int targetWidth, int targetHeight) {
        String key = sharedSourceKey(sourceName, targetWidth, targetHeight);
        SharedNdiSource shared = sharedSources.get(key);
        if (shared == null) {
            shared = new SharedNdiSource(key, new NdiVideoSource(sourceName, targetWidth, targetHeight));
            sharedSources.put(key, shared);
        }
        return shared.retain();
    }

    private synchronized void releaseSharedSource(SharedNdiSource shared, SharedNdiLease lease) {
        if (!shared.release(lease)) {
            return;
        }
        if (sharedSources.remove(shared.key(), shared)) {
            shared.disposeDelegate();
        }
    }

    private synchronized void clearSharedSources() {
        for (SharedNdiSource shared : sharedSources.values()) {
            shared.disposeDelegate();
        }
        sharedSources.clear();
    }

    private static String sharedSourceKey(String sourceName, int targetWidth, int targetHeight) {
        return sourceName + "@" + targetWidth + "x" + targetHeight;
    }

    private final class SharedNdiSource {
        private final String key;
        private final VideoSource delegate;
        private final Set<SharedNdiLease> leases = Collections.newSetFromMap(new IdentityHashMap<>());

        private SharedNdiSource(String key, VideoSource delegate) {
            this.key = key;
            this.delegate = delegate;
        }

        private String key() {
            return key;
        }

        private SharedNdiLease retain() {
            SharedNdiLease lease = new SharedNdiLease(this);
            leases.add(lease);
            updateDelegateActive();
            return lease;
        }

        private boolean release(SharedNdiLease lease) {
            leases.remove(lease);
            updateDelegateActive();
            return leases.isEmpty();
        }

        private void setLeaseActive(SharedNdiLease lease, boolean active) {
            if (!leases.contains(lease)) {
                return;
            }
            lease.active = active;
            updateDelegateActive();
        }

        private void updateDelegateActive() {
            for (SharedNdiLease lease : leases) {
                if (lease.active) {
                    delegate.setActive(true);
                    return;
                }
            }
            delegate.setActive(false);
        }

        private void disposeDelegate() {
            delegate.dispose();
        }
    }

    private final class SharedNdiLease implements VideoSource {
        private final SharedNdiSource shared;
        private boolean active = true;
        private boolean disposed;

        private SharedNdiLease(SharedNdiSource shared) {
            this.shared = shared;
        }

        @Override
        public int getWidth() {
            return shared.delegate.getWidth();
        }

        @Override
        public int getHeight() {
            return shared.delegate.getHeight();
        }

        @Override
        public void tick() {
            shared.delegate.tick();
        }

        @Override
        public void setActive(boolean active) {
            synchronized (NdiProvider.this) {
                if (!disposed) {
                    shared.setLeaseActive(this, active);
                }
            }
        }

        @Override
        public VideoFrame getCurrentFrame() {
            return shared.delegate.getCurrentFrame();
        }

        @Override
        public void dispose() {
            synchronized (NdiProvider.this) {
                if (disposed) {
                    return;
                }
                disposed = true;
                releaseSharedSource(shared, this);
            }
        }
    }

    @Override
    public List<ProviderConfigOption> getConfigOptions() {
        return List.of(
                new ProviderConfigOption(
                        "enableNdi",
                        "Enable NDI",
                        "Turns NDI input on for this client.",
                        ProviderConfigOptionType.BOOLEAN,
                        String.valueOf(ModConfig.ENABLE_NDI.get()),
                        true
                ),
                new ProviderConfigOption(
                        "ndiDefaultSource",
                        "Default NDI source",
                        "Fallback source name when a wall has no explicit binding.",
                        ProviderConfigOptionType.STRING,
                        ModConfig.NDI_DEFAULT_SOURCE.get(),
                        true
                ),
                new ProviderConfigOption(
                        "ndiAutoSelectFirst",
                        "Auto-select first source",
                        "Use the first discovered NDI source when nothing else is configured.",
                        ProviderConfigOptionType.BOOLEAN,
                        String.valueOf(ModConfig.NDI_AUTO_SELECT_FIRST.get()),
                        true
                ),
                new ProviderConfigOption(
                        "ndiReceiveTimeoutMs",
                        "Receive timeout (ms)",
                        "Per-frame NDI receive timeout.",
                        ProviderConfigOptionType.INTEGER,
                        String.valueOf(ModConfig.NDI_RECEIVE_TIMEOUT_MS.get()),
                        true
                ),
                new ProviderConfigOption(
                        "ndiDiscoveryIntervalMs",
                        "Discovery interval (ms)",
                        "How often to refresh the NDI source list.",
                        ProviderConfigOptionType.INTEGER,
                        String.valueOf(ModConfig.NDI_DISCOVERY_INTERVAL_MS.get()),
                        true
                )
        );
    }
}
