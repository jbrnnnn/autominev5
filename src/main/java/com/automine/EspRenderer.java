package com.automine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Block ESP tembus tembok. Memakai custom RenderPipeline (NO_DEPTH_TEST)
 * mengikuti contoh resmi Fabric Docs "Rendering in the World" untuk 1.21.11.
 */
public final class EspRenderer {
    private static final int MAX_BOXES = 1500;

    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(AutoMineClient.MOD_ID, "pipeline/esp_filled_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static BufferBuilder buffer;
    private static MappableRingBuffer vertexBuffer;
    private static int boxCount;

    private EspRenderer() {}

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(EspRenderer::onRender);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> close());
    }

    private static void onRender(WorldRenderContext context) {
        AutoMineConfig cfg = AutoMineConfig.get();
        if (!cfg.esp) return;
        if (AutoBot.mode == AutoBot.Mode.OFF && !cfg.espAlways) return;

        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        boxCount = 0;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc pose = matrices.last().pose();

        // semua ore / kayu yang ketemu
        for (AutoBot.Found f : AutoBot.found) {
            if (boxCount >= MAX_BOXES) break;
            BlockPos p = f.pos();
            float r = ((f.color() >> 16) & 255) / 255f, g = ((f.color() >> 8) & 255) / 255f, b = (f.color() & 255) / 255f;
            addBox(pose, p.getX() - 0.005f, p.getY() - 0.005f, p.getZ() - 0.005f,
                    p.getX() + 1.005f, p.getY() + 1.005f, p.getZ() + 1.005f, r, g, b, 0.30f);
        }

        // target aktif: merah terang
        BlockPos t = AutoBot.target;
        if (t != null && AutoBot.mode != AutoBot.Mode.OFF) {
            addBox(pose, t.getX() - 0.02f, t.getY() - 0.02f, t.getZ() - 0.02f,
                    t.getX() + 1.02f, t.getY() + 1.02f, t.getZ() + 1.02f, 1f, 0.15f, 0.15f, 0.55f);
        }

        // jalur
        if (cfg.showPath && AutoBot.mode != AutoBot.Mode.OFF) {
            var path = AutoBot.path;
            int end = Math.min(path.size(), AutoBot.pathIdx + 200);
            for (int i = Math.max(0, AutoBot.pathIdx); i < end; i++) {
                BlockPos p = path.get(i);
                addBox(pose, p.getX() + 0.38f, p.getY(), p.getZ() + 0.38f,
                        p.getX() + 0.62f, p.getY() + 0.25f, p.getZ() + 0.62f, 0.2f, 1f, 0.3f, 0.7f);
            }
        }
        matrices.popPose();

        if (boxCount > 0 && buffer != null) {
            drawFilledThroughWalls(Minecraft.getInstance());
        }
    }

    private static void addBox(Matrix4fc m, float x0, float y0, float z0, float x1, float y1, float z1,
                               float r, float g, float b, float a) {
        if (buffer == null) {
            buffer = new BufferBuilder(allocator, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());
        }
        boxCount++;
        // depan
        buffer.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
        // belakang
        buffer.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
        // kiri
        buffer.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        // kanan
        buffer.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        // atas
        buffer.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        // bawah
        buffer.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
    }

    private static void drawFilledThroughWalls(Minecraft client) {
        RenderPipeline pipeline = FILLED_THROUGH_WALLS;
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);
        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

        vertexBuffer.rotate();
        buffer = null;
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> AutoMineClient.MOD_ID + " esp vertex buffer",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }
        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
                             MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> AutoMineClient.MOD_ID + " esp render",
                client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
        }
        builtBuffer.close();
    }

    private static void close() {
        allocator.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
