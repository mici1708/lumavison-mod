package fr.lumavision.client.texture;

import fr.lumavision.video.VideoFrame;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;

/**
 * Low-stall RGBA texture uploader backed by a small Pixel Buffer Object ring.
 */
final class PboTextureUploader implements AutoCloseable {

    private static final int PBO_COUNT = 3;
    private static final long FENCE_TIMEOUT_NS = 1_000_000L;

    private final Slot[] slots = new Slot[PBO_COUNT];
    private int ringIndex;
    private final boolean persistentMapSupported;

    PboTextureUploader() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot(GL15.glGenBuffers());
        }
        persistentMapSupported = detectPersistentMapSupport();
    }

    void upload(int textureId, VideoFrame frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int size = width * height * Integer.BYTES;
        if (textureId <= 0 || width <= 0 || height <= 0 || size <= 0) {
            return;
        }

        Slot slot = slots[ringIndex];
        ringIndex = (ringIndex + 1) % slots.length;

        waitSlotFence(slot);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo);
        ensureCapacity(slot, size);

        ByteBuffer mapped = slot.persistent;
        if (mapped == null) {
            mapped = GL30.glMapBufferRange(
                    GL21.GL_PIXEL_UNPACK_BUFFER,
                    0L,
                    size,
                    GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT
            );
            if (mapped == null) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                return;
            }
        }

        frame.writeNativeRgbaTo(mapped);

        if (slot.persistent == null) {
            GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                0L
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private void ensureCapacity(Slot slot, int size) {
        if (size <= slot.capacity) {
            return;
        }

        if (slot.persistent != null) {
            GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
            slot.persistent = null;
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(slot.pbo);
            slot.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo);
        }

        if (persistentMapSupported) {
            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size, flags);
            } else {
                ARBBufferStorage.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size, flags);
            }
            slot.persistent = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, size, flags);
            if (slot.persistent == null) {
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size, GL15.GL_STREAM_DRAW);
            }
        } else {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size, GL15.GL_STREAM_DRAW);
        }
        slot.capacity = size;
    }

    private static boolean detectPersistentMapSupport() {
        try {
            var capabilities = GL.getCapabilities();
            return capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void waitSlotFence(Slot slot) {
        long fence = slot.fence;
        if (fence == 0L) {
            return;
        }
        int status = GL32.glClientWaitSync(fence, 0, 0L);
        if (status == GL32.GL_TIMEOUT_EXPIRED) {
            GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS);
        }
        GL32.glDeleteSync(fence);
        slot.fence = 0L;
    }

    @Override
    public void close() {
        for (Slot slot : slots) {
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }
            if (slot.persistent != null) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, slot.pbo);
                GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
                slot.persistent = null;
            }
            if (slot.pbo != 0) {
                GL15.glDeleteBuffers(slot.pbo);
                slot.pbo = 0;
            }
            slot.capacity = 0;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private static final class Slot {
        private int pbo;
        private int capacity;
        private long fence;
        private ByteBuffer persistent;

        private Slot(int pbo) {
            this.pbo = pbo;
        }
    }
}
