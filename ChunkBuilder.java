/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  net.fabricmc.api.EnvType
 *  net.fabricmc.api.Environment
 */
package net.minecraft.client.render.chunk;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.primitives.Doubles;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.chunk.BlockBufferBuilderStorage;
import net.minecraft.client.render.chunk.ChunkOcclusionData;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Util;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Environment(value=EnvType.CLIENT)
public class ChunkBuilder {
    private static final Logger LOGGER = LogManager.getLogger();
    private final PriorityQueue<BuiltChunk.Task> rebuildQueue = Queues.newPriorityQueue();
    private final Queue<BlockBufferBuilderStorage> threadBuffers;
    private final Queue<Runnable> uploadQueue = Queues.newConcurrentLinkedQueue();
    private volatile int queuedTaskCount;
    private volatile int bufferCount;
    private final BlockBufferBuilderStorage buffers;
    private final TaskExecutor<Runnable> mailbox;
    private final Executor executor;
    private World world;
    private final WorldRenderer worldRenderer;
    private Vec3d cameraPosition = Vec3d.ZERO;

    public ChunkBuilder(World world, WorldRenderer worldRenderer, Executor executor, boolean is64Bits, BlockBufferBuilderStorage buffers) {
        this.world = world;
        this.worldRenderer = worldRenderer;
        int i = Math.max(1, (int)((double)Runtime.getRuntime().maxMemory() * 0.3) / (RenderLayer.getBlockLayers().stream().mapToInt(RenderLayer::getExpectedBufferSize).sum() * 4) - 1);
        int j = Runtime.getRuntime().availableProcessors();
        int k = is64Bits ? j : Math.min(j, 4);
        int l = Math.max(1, Math.min(k, i));
        this.buffers = buffers;
        ArrayList<BlockBufferBuilderStorage> list = Lists.newArrayListWithExpectedSize(l);
        try {
            for (int m = 0; m < l; ++m) {
                list.add(new BlockBufferBuilderStorage());
            }
        }
        catch (OutOfMemoryError outOfMemoryError) {
            LOGGER.warn("Allocated only {}/{} buffers", (Object)list.size(), (Object)l);
            int n = Math.min(list.size() * 2 / 3, list.size() - 1);
            for (int o = 0; o < n; ++o) {
                list.remove(list.size() - 1);
            }
            System.gc();
        }
        this.threadBuffers = Queues.newArrayDeque(list);
        this.bufferCount = this.threadBuffers.size();
        this.executor = executor;
        this.mailbox = TaskExecutor.create(executor, "Chunk Renderer");
        this.mailbox.send(this::scheduleRunTasks);
    }

    public void setWorld(World world) {
        this.world = world;
    }

    private void scheduleRunTasks() {
        if (this.threadBuffers.isEmpty()) {
            return;
        }
        BuiltChunk.Task lv = this.rebuildQueue.poll();
        if (lv == null) {
            return;
        }
        BlockBufferBuilderStorage lv2 = this.threadBuffers.poll();
        this.queuedTaskCount = this.rebuildQueue.size();
        this.bufferCount = this.threadBuffers.size();
        ((CompletableFuture)CompletableFuture.runAsync(() -> {}, this.executor).thenCompose(void_ -> lv.run(lv2))).whenComplete((arg2, throwable) -> {
            if (throwable != null) {
                CrashReport lv = CrashReport.create(throwable, "Batching chunks");
                MinecraftClient.getInstance().setCrashReport(MinecraftClient.getInstance().addDetailsToCrashReport(lv));
                return;
            }
            this.mailbox.send(() -> {
                if (arg2 == Result.SUCCESSFUL) {
                    lv2.clear();
                } else {
                    lv2.reset();
                }
                this.threadBuffers.add(lv2);
                this.bufferCount = this.threadBuffers.size();
                this.scheduleRunTasks();
            });
        });
    }

    public String getDebugString() {
        return String.format("pC: %03d, pU: %02d, aB: %02d", this.queuedTaskCount, this.uploadQueue.size(), this.bufferCount);
    }

    public void setCameraPosition(Vec3d cameraPosition) {
        this.cameraPosition = cameraPosition;
    }

    public Vec3d getCameraPosition() {
        return this.cameraPosition;
    }

    public boolean upload() {
        Runnable runnable;
        boolean bl = false;
        while ((runnable = this.uploadQueue.poll()) != null) {
            runnable.run();
            bl = true;
        }
        return bl;
    }

    public void rebuild(BuiltChunk chunk) {
        chunk.rebuild();
    }

    public void reset() {
        this.clear();
    }

    public void send(BuiltChunk.Task task) {
        this.mailbox.send(() -> {
            this.rebuildQueue.offer(task);
            this.queuedTaskCount = this.rebuildQueue.size();
            this.scheduleRunTasks();
        });
    }

    public CompletableFuture<Void> scheduleUpload(BufferBuilder buffer, VertexBuffer glBuffer) {
        return CompletableFuture.runAsync(() -> {}, this.uploadQueue::add).thenCompose(void_ -> this.upload(buffer, glBuffer));
    }

    private CompletableFuture<Void> upload(BufferBuilder buffer, VertexBuffer glBuffer) {
        return glBuffer.submitUpload(buffer);
    }

    private void clear() {
        while (!this.rebuildQueue.isEmpty()) {
            BuiltChunk.Task lv = this.rebuildQueue.poll();
            if (lv == null) continue;
            lv.cancel();
        }
        this.queuedTaskCount = 0;
    }

    public boolean isEmpty() {
        return this.queuedTaskCount == 0 && this.uploadQueue.isEmpty();
    }

    public void stop() {
        this.clear();
        this.mailbox.close();
        this.threadBuffers.clear();
    }

    @Environment(value=EnvType.CLIENT)
    public static class ChunkData {
        public static final ChunkData EMPTY = new ChunkData(){

            @Override
            public boolean isVisibleThrough(Direction arg, Direction arg2) {
                return false;
            }
        };
        private final Set<RenderLayer> nonEmptyLayers = new ObjectArraySet<RenderLayer>();
        private final Set<RenderLayer> initializedLayers = new ObjectArraySet<RenderLayer>();
        private boolean empty = true;
        private final List<BlockEntity> blockEntities = Lists.newArrayList();
        private ChunkOcclusionData occlusionGraph = new ChunkOcclusionData();
        @Nullable
        private BufferBuilder.class_5594 bufferState;

        public boolean isEmpty() {
            return this.empty;
        }

        public boolean isEmpty(RenderLayer layer) {
            return !this.nonEmptyLayers.contains(layer);
        }

        public List<BlockEntity> getBlockEntities() {
            return this.blockEntities;
        }

        public boolean isVisibleThrough(Direction arg, Direction arg2) {
            return this.occlusionGraph.isVisibleThrough(arg, arg2);
        }
    }

    @Environment(value=EnvType.CLIENT)
    static enum Result {
        SUCCESSFUL,
        CANCELLED;

    }

    @Environment(value=EnvType.CLIENT)
    public class BuiltChunk {
        public final AtomicReference<ChunkData> data = new AtomicReference<ChunkData>(ChunkData.EMPTY);
        @Nullable
        private RebuildTask rebuildTask;
        @Nullable
        private SortTask sortTask;
        private final Set<BlockEntity> blockEntities = Sets.newHashSet();
        private final Map<RenderLayer, VertexBuffer> buffers = RenderLayer.getBlockLayers().stream().collect(Collectors.toMap(arg -> arg, arg -> new VertexBuffer()));
        public Box boundingBox;
        private int rebuildFrame = -1;
        private boolean needsRebuild = true;
        private final BlockPos.Mutable origin = new BlockPos.Mutable(-1, -1, -1);
        private final BlockPos.Mutable[] neighborPositions = Util.make(new BlockPos.Mutable[6], args -> {
            for (int i = 0; i < ((BlockPos.Mutable[])args).length; ++i) {
                args[i] = new BlockPos.Mutable();
            }
        });
        private boolean needsImportantRebuild;

        private boolean isChunkNonEmpty(BlockPos pos) {
            return ChunkBuilder.this.world.getChunk(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()), ChunkStatus.FULL, false) != null;
        }

        public boolean shouldBuild() {
            int i = 24;
            if (this.getSquaredCameraDistance() > 576.0) {
                return this.isChunkNonEmpty(this.neighborPositions[Direction.WEST.ordinal()]) && this.isChunkNonEmpty(this.neighborPositions[Direction.NORTH.ordinal()]) && this.isChunkNonEmpty(this.neighborPositions[Direction.EAST.ordinal()]) && this.isChunkNonEmpty(this.neighborPositions[Direction.SOUTH.ordinal()]);
            }
            return true;
        }

        public boolean setRebuildFrame(int frame) {
            if (this.rebuildFrame == frame) {
                return false;
            }
            this.rebuildFrame = frame;
            return true;
        }

        public VertexBuffer getBuffer(RenderLayer layer) {
            return this.buffers.get(layer);
        }

        public void setOrigin(int x, int y, int z) {
            if (x == this.origin.getX() && y == this.origin.getY() && z == this.origin.getZ()) {
                return;
            }
            this.clear();
            this.origin.set(x, y, z);
            this.boundingBox = new Box(x, y, z, x + 16, y + 16, z + 16);
            for (Direction lv : Direction.values()) {
                this.neighborPositions[lv.ordinal()].set(this.origin).move(lv, 16);
            }
        }

        protected double getSquaredCameraDistance() {
            Camera lv = MinecraftClient.getInstance().gameRenderer.getCamera();
            double d = this.boundingBox.minX + 8.0 - lv.getPos().x;
            double e = this.boundingBox.minY + 8.0 - lv.getPos().y;
            double f = this.boundingBox.minZ + 8.0 - lv.getPos().z;
            return d * d + e * e + f * f;
        }

        private void beginBufferBuilding(BufferBuilder buffer) {
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
        }

        public ChunkData getData() {
            return this.data.get();
        }

        private void clear() {
            this.cancel();
            this.data.set(ChunkData.EMPTY);
            this.needsRebuild = true;
        }

        public void delete() {
            this.clear();
            this.buffers.values().forEach(VertexBuffer::close);
        }

        public BlockPos getOrigin() {
            return this.origin;
        }

        public void scheduleRebuild(boolean important) {
            boolean bl2 = this.needsRebuild;
            this.needsRebuild = true;
            this.needsImportantRebuild = important | (bl2 && this.needsImportantRebuild);
        }

        public void cancelRebuild() {
            this.needsRebuild = false;
            this.needsImportantRebuild = false;
        }

        public boolean needsRebuild() {
            return this.needsRebuild;
        }

        public boolean needsImportantRebuild() {
            return this.needsRebuild && this.needsImportantRebuild;
        }

        public BlockPos getNeighborPosition(Direction direction) {
            return this.neighborPositions[direction.ordinal()];
        }

        public boolean scheduleSort(RenderLayer layer, ChunkBuilder chunkRenderer) {
            ChunkData lv = this.getData();
            if (this.sortTask != null) {
                this.sortTask.cancel();
            }
            if (!lv.initializedLayers.contains(layer)) {
                return false;
            }
            this.sortTask = new SortTask(this.getSquaredCameraDistance(), lv);
            chunkRenderer.send(this.sortTask);
            return true;
        }

        protected void cancel() {
            if (this.rebuildTask != null) {
                this.rebuildTask.cancel();
                this.rebuildTask = null;
            }
            if (this.sortTask != null) {
                this.sortTask.cancel();
                this.sortTask = null;
            }
        }

        public Task createRebuildTask() {
            this.cancel();
            BlockPos lv = this.origin.toImmutable();
            boolean i = true;
            ChunkRendererRegion lv2 = ChunkRendererRegion.create(ChunkBuilder.this.world, lv.add(-1, -1, -1), lv.add(16, 16, 16), 1);
            this.rebuildTask = new RebuildTask(this.getSquaredCameraDistance(), lv2);
            return this.rebuildTask;
        }

        public void scheduleRebuild(ChunkBuilder chunkRenderer) {
            Task lv = this.createRebuildTask();
            chunkRenderer.send(lv);
        }

        private void setNoCullingBlockEntities(Set<BlockEntity> noCullingBlockEntities) {
            HashSet<BlockEntity> set2 = Sets.newHashSet(noCullingBlockEntities);
            HashSet<BlockEntity> set3 = Sets.newHashSet(this.blockEntities);
            set2.removeAll(this.blockEntities);
            set3.removeAll(noCullingBlockEntities);
            this.blockEntities.clear();
            this.blockEntities.addAll(noCullingBlockEntities);
            ChunkBuilder.this.worldRenderer.updateNoCullingBlockEntities(set3, set2);
        }

        public void rebuild() {
            Task lv = this.createRebuildTask();
            lv.run(ChunkBuilder.this.buffers);
        }

        @Environment(value=EnvType.CLIENT)
        abstract class Task
        implements Comparable<Task> {
            protected final double distance;
            protected final AtomicBoolean cancelled = new AtomicBoolean(false);

            public Task(double d) {
                this.distance = d;
            }

            public abstract CompletableFuture<Result> run(BlockBufferBuilderStorage var1);

            public abstract void cancel();

            @Override
            public int compareTo(Task arg) {
                return Doubles.compare(this.distance, arg.distance);
            }

            @Override
            public /* synthetic */ int compareTo(Object object) {
                return this.compareTo((Task)object);
            }
        }

        @Environment(value=EnvType.CLIENT)
        class SortTask
        extends Task {
            private final ChunkData data;

            public SortTask(double d, ChunkData arg2) {
                super(d);
                this.data = arg2;
            }

            @Override
            public CompletableFuture<Result> run(BlockBufferBuilderStorage buffers) {
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                if (!BuiltChunk.this.shouldBuild()) {
                    this.cancelled.set(true);
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                Vec3d lv = ChunkBuilder.this.getCameraPosition();
                float f = (float)lv.x;
                float g = (float)lv.y;
                float h = (float)lv.z;
                BufferBuilder.class_5594 lv2 = this.data.bufferState;
                if (lv2 == null || !this.data.nonEmptyLayers.contains(RenderLayer.getTranslucent())) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                BufferBuilder lv3 = buffers.get(RenderLayer.getTranslucent());
                BuiltChunk.this.beginBufferBuilding(lv3);
                lv3.restoreState(lv2);
                lv3.method_31948(f - (float)BuiltChunk.this.origin.getX(), g - (float)BuiltChunk.this.origin.getY(), h - (float)BuiltChunk.this.origin.getZ());
                this.data.bufferState = lv3.popState();
                lv3.end();
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                CompletionStage completableFuture = ChunkBuilder.this.scheduleUpload(buffers.get(RenderLayer.getTranslucent()), BuiltChunk.this.getBuffer(RenderLayer.getTranslucent())).thenApply(void_ -> Result.CANCELLED);
                return ((CompletableFuture)completableFuture).handle((arg, throwable) -> {
                    if (throwable != null && !(throwable instanceof CancellationException) && !(throwable instanceof InterruptedException)) {
                        MinecraftClient.getInstance().setCrashReport(CrashReport.create(throwable, "Rendering chunk"));
                    }
                    return this.cancelled.get() ? Result.CANCELLED : Result.SUCCESSFUL;
                });
            }

            @Override
            public void cancel() {
                this.cancelled.set(true);
            }
        }

        @Environment(value=EnvType.CLIENT)
        class RebuildTask
        extends Task {
            @Nullable
            protected ChunkRendererRegion region;

            public RebuildTask(double d, @Nullable ChunkRendererRegion arg2) {
                super(d);
                this.region = arg2;
            }

            @Override
            public CompletableFuture<Result> run(BlockBufferBuilderStorage buffers) {
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                if (!BuiltChunk.this.shouldBuild()) {
                    this.region = null;
                    BuiltChunk.this.scheduleRebuild(false);
                    this.cancelled.set(true);
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                Vec3d lv = ChunkBuilder.this.getCameraPosition();
                float f = (float)lv.x;
                float g = (float)lv.y;
                float h = (float)lv.z;
                ChunkData lv2 = new ChunkData();
                Set<BlockEntity> set = this.render(f, g, h, lv2, buffers);
                BuiltChunk.this.setNoCullingBlockEntities(set);
                if (this.cancelled.get()) {
                    return CompletableFuture.completedFuture(Result.CANCELLED);
                }
                ArrayList list2 = Lists.newArrayList();
                lv2.initializedLayers.forEach(arg2 -> list2.add(ChunkBuilder.this.scheduleUpload(buffers.get((RenderLayer)arg2), BuiltChunk.this.getBuffer((RenderLayer)arg2))));
                return Util.combine(list2).handle((list, throwable) -> {
                    if (throwable != null && !(throwable instanceof CancellationException) && !(throwable instanceof InterruptedException)) {
                        MinecraftClient.getInstance().setCrashReport(CrashReport.create(throwable, "Rendering chunk"));
                    }
                    if (this.cancelled.get()) {
                        return Result.CANCELLED;
                    }
                    BuiltChunk.this.data.set(lv2);
                    return Result.SUCCESSFUL;
                });
            }

            private Set<BlockEntity> render(float cameraX, float cameraY, float cameraZ, ChunkData data, BlockBufferBuilderStorage buffers) {
                boolean i = true;
                BlockPos lv = BuiltChunk.this.origin.toImmutable();
                BlockPos lv2 = lv.add(15, 15, 15);
                ChunkOcclusionDataBuilder lv3 = new ChunkOcclusionDataBuilder();
                HashSet<BlockEntity> set = Sets.newHashSet();
                ChunkRendererRegion lv4 = this.region;
                this.region = null;
                MatrixStack lv5 = new MatrixStack();
                if (lv4 != null) {
                    BlockModelRenderer.enableBrightnessCache();
                    Random random = new Random();
                    BlockRenderManager lv6 = MinecraftClient.getInstance().getBlockRenderManager();
                    for (BlockPos lv7 : BlockPos.iterate(lv, lv2)) {
                        FluidState lv10;
                        BlockEntity lv9;
                        BlockState lv8 = lv4.getBlockState(lv7);
                        if (lv8.isOpaqueFullCube(lv4, lv7)) {
                            lv3.markClosed(lv7);
                        }
                        if (lv8.hasBlockEntity() && (lv9 = lv4.getBlockEntity(lv7, WorldChunk.CreationType.CHECK)) != null) {
                            this.addBlockEntity(data, set, lv9);
                        }
                        if (!(lv10 = lv4.getFluidState(lv7)).isEmpty()) {
                            RenderLayer lv11 = RenderLayers.getFluidLayer(lv10);
                            BufferBuilder lv12 = buffers.get(lv11);
                            if (data.initializedLayers.add(lv11)) {
                                BuiltChunk.this.beginBufferBuilding(lv12);
                            }
                            if (lv6.renderFluid(lv7, lv4, lv12, lv10)) {
                                data.empty = false;
                                data.nonEmptyLayers.add(lv11);
                            }
                        }
                        if (lv8.getRenderType() == BlockRenderType.INVISIBLE) continue;
                        RenderLayer lv13 = RenderLayers.getBlockLayer(lv8);
                        BufferBuilder lv14 = buffers.get(lv13);
                        if (data.initializedLayers.add(lv13)) {
                            BuiltChunk.this.beginBufferBuilding(lv14);
                        }
                        lv5.push();
                        lv5.translate(lv7.getX() & 0xF, lv7.getY() & 0xF, lv7.getZ() & 0xF);
                        if (lv6.renderBlock(lv8, lv7, lv4, lv5, lv14, true, random)) {
                            data.empty = false;
                            data.nonEmptyLayers.add(lv13);
                        }
                        lv5.pop();
                    }
                    if (data.nonEmptyLayers.contains(RenderLayer.getTranslucent())) {
                        BufferBuilder lv15 = buffers.get(RenderLayer.getTranslucent());
                        lv15.method_31948(cameraX - (float)lv.getX(), cameraY - (float)lv.getY(), cameraZ - (float)lv.getZ());
                        data.bufferState = lv15.popState();
                    }
                    data.initializedLayers.stream().map(buffers::get).forEach(BufferBuilder::end);
                    BlockModelRenderer.disableBrightnessCache();
                }
                data.occlusionGraph = lv3.build();
                return set;
            }

            private <E extends BlockEntity> void addBlockEntity(ChunkData data, Set<BlockEntity> blockEntities, E blockEntity) {
                BlockEntityRenderer<E> lv = MinecraftClient.getInstance().method_31975().get(blockEntity);
                if (lv != null) {
                    data.blockEntities.add(blockEntity);
                    if (lv.rendersOutsideBoundingBox(blockEntity)) {
                        blockEntities.add(blockEntity);
                    }
                }
            }

            @Override
            public void cancel() {
                this.region = null;
                if (this.cancelled.compareAndSet(false, true)) {
                    BuiltChunk.this.scheduleRebuild(false);
                }
            }
        }
    }
}

