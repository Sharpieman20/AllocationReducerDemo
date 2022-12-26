/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  net.fabricmc.api.EnvType
 *  net.fabricmc.api.Environment
 */
package net.minecraft.client.render.chunk;

import javax.annotation.Nullable;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.level.ColorResolver;

@Environment(value=EnvType.CLIENT)
public class ChunkRendererRegion
implements BlockRenderView {
    protected final int chunkXOffset;
    protected final int chunkZOffset;
    protected final BlockPos offset;
    protected final int xSize;
    protected final int ySize;
    protected final int zSize;
    protected final WorldChunk[][] chunks;
    protected final BlockState[] blockStates;
    protected final FluidState[] fluidStates;
    protected final World world;

    @Nullable
    public static ChunkRendererRegion create(World world, BlockPos startPos, BlockPos endPos, int chunkRadius) {
        int j = ChunkSectionPos.getSectionCoord(startPos.getX() - chunkRadius);
        int k = ChunkSectionPos.getSectionCoord(startPos.getZ() - chunkRadius);
        int l = ChunkSectionPos.getSectionCoord(endPos.getX() + chunkRadius);
        int m = ChunkSectionPos.getSectionCoord(endPos.getZ() + chunkRadius);
        WorldChunk[][] lvs = new WorldChunk[l - j + 1][m - k + 1];
        for (int n = j; n <= l; ++n) {
            for (int o = k; o <= m; ++o) {
                lvs[n - j][o - k] = world.getChunk(n, o);
            }
        }
        if (ChunkRendererRegion.method_30000(startPos, endPos, j, k, lvs)) {
            return null;
        }
        boolean p = true;
        BlockPos lv = startPos.add(-1, -1, -1);
        BlockPos lv2 = endPos.add(1, 1, 1);
        return new ChunkRendererRegion(world, j, k, lvs, lv, lv2);
    }

    public static boolean method_30000(BlockPos arg, BlockPos arg2, int i, int j, WorldChunk[][] args) {
        for (int k = ChunkSectionPos.getSectionCoord(arg.getX()); k <= ChunkSectionPos.getSectionCoord(arg2.getX()); ++k) {
            for (int l = ChunkSectionPos.getSectionCoord(arg.getZ()); l <= ChunkSectionPos.getSectionCoord(arg2.getZ()); ++l) {
                WorldChunk lv = args[k - i][l - j];
                if (lv.areSectionsEmptyBetween(arg.getY(), arg2.getY())) continue;
                return false;
            }
        }
        return true;
    }


    static class PooledObjectHolder<T> {

        private static ConcurrentHashMap<Integer, ConcurrentLinkedQueue<PooledObjectHolder<T>>> freeHoldersBySize;
        
        private static ConcurrentLinkedQueue<PooledObjectHolder<T>> freeHolders;

        private T[] objects;
        
        public PooledObjectHolder(int size) {
            objects = new T[size];
        }

        private static ConcurrentLinkedQueue<PooledObjectHolder<T>> getQueue(int size) {
            if (!freeHoldersBySize.contains(size)) {
                freeHoldersBySize.putIfAbsent(size, new ConcurrentLinkedQueue<PooledObjectHolder<T>>());
            }
            return freeHoldersBySize.get(size);
        }

        public static BlockStateHolder<T> create(int size) {
            ConcurrentLinkedQueue<PooledObjectHolder<T>> freeHolders = getQueue(size);
            PooledObjectHolder<T> myHolder = freeHolders.poll();
            if (myHolder == null) {
                myHolder = new PooledObjectHolder<T>(size);
            }
            return myHolder;
        }

        public void set(int index, T value) {
            objects[index] = value;
        }

        private void release() {
            for (int i = 0; i < objects.length; i++) {
                objects[i] = null;
            }
            getQueue(objects.length).add(this);
        }
    }

    public ChunkRendererRegion(World world, int chunkX, int chunkZ, WorldChunk[][] chunks, BlockPos startPos, BlockPos endPos) {
        this.world = world;
        this.chunkXOffset = chunkX;
        this.chunkZOffset = chunkZ;
        this.chunks = chunks;
        this.offset = startPos;
        this.xSize = endPos.getX() - startPos.getX() + 1;
        this.ySize = endPos.getY() - startPos.getY() + 1;
        this.zSize = endPos.getZ() - startPos.getZ() + 1;
        this.blockStates = PooledObjectHolder<BlockState>.create(this.xSize * this.ySize * this.zSize);
        this.fluidStates = PooledObjectHolder<FluidState>.create(this.xSize * this.ySize * this.zSize);
        for (BlockPos lv : BlockPos.iterate(startPos, endPos)) {
            int k = ChunkSectionPos.getSectionCoord(lv.getX()) - chunkX;
            int l = ChunkSectionPos.getSectionCoord(lv.getZ()) - chunkZ;
            WorldChunk lv2 = chunks[k][l];
            int m = this.getIndex(lv);
            this.blockStates.set(m, lv2.getBlockState(lv));
            this.fluidStates.set(m, lv2.getFluidState(lv));
        }
    }

    protected final int getIndex(BlockPos pos) {
        return this.getIndex(pos.getX(), pos.getY(), pos.getZ());
    }

    protected int getIndex(int x, int y, int z) {
        int l = x - this.offset.getX();
        int m = y - this.offset.getY();
        int n = z - this.offset.getZ();
        return n * this.xSize * this.ySize + m * this.xSize + l;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.blockStates[this.getIndex(pos)];
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.fluidStates[this.getIndex(pos)];
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return this.world.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return this.world.getLightingProvider();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.getBlockEntity(pos, WorldChunk.CreationType.IMMEDIATE);
    }

    @Nullable
    public BlockEntity getBlockEntity(BlockPos arg, WorldChunk.CreationType arg2) {
        int i = ChunkSectionPos.getSectionCoord(arg.getX()) - this.chunkXOffset;
        int j = ChunkSectionPos.getSectionCoord(arg.getZ()) - this.chunkZOffset;
        return this.chunks[i][j].getBlockEntity(arg, arg2);
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return this.world.getColor(pos, colorResolver);
    }

    @Override
    public int getSectionCount() {
        return this.world.getSectionCount();
    }

    @Override
    public int getBottomSectionLimit() {
        return this.world.getBottomSectionLimit();
    }

    public void destroy() {
        this.blockStates.release();
        this.fluidStates.release();
    }
}

