package me.jellysquid.mods.phosphor.mixin.chunk.light;

import me.jellysquid.mods.phosphor.common.chunk.level.LevelPropagatorExtended;
import me.jellysquid.mods.phosphor.common.chunk.light.LightProviderBlockAccess;
import me.jellysquid.mods.phosphor.common.chunk.light.LightStorageAccess;
import me.jellysquid.mods.phosphor.common.util.LightUtil;
import me.jellysquid.mods.phosphor.common.util.math.ChunkSectionPosHelper;
import me.jellysquid.mods.phosphor.common.util.math.DirectionHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.ChunkSkyLightProvider;
import net.minecraft.world.chunk.light.SkyLightStorage;
import org.spongepowered.asm.mixin.*;

@Mixin(ChunkSkyLightProvider.class)
public abstract class MixinChunkSkyLightProvider extends ChunkLightProvider<SkyLightStorage.Data, SkyLightStorage>
        implements LevelPropagatorExtended, LightProviderBlockAccess {
    private static final BlockState AIR_BLOCK = Blocks.AIR.getDefaultState();

    @Shadow
    @Final
    private static Direction[] HORIZONTAL_DIRECTIONS;

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    public MixinChunkSkyLightProvider(ChunkProvider chunkProvider, LightType type, SkyLightStorage lightStorage) {
        super(chunkProvider, type, lightStorage);
    }

    /**
     * @author JellySquid
     * @reason Use optimized method below
     */
    @Override
    @Overwrite
    public int getPropagatedLevel(long fromId, long toId, int currentLevel) {
        return this.getPropagatedLevel(fromId, null, toId, currentLevel, null);
    }

    /**
     * This breaks up the call to method_20479 into smaller parts so we do not have to pass a mutable heap object
     * to the method in order to extract the light result. This has a few other advantages, allowing us to:
     * - Avoid the de-optimization that occurs from allocating and passing a heap object
     * - Avoid unpacking coordinates twice for both the call to method_20479 and method_20710.
     * - Avoid the the specific usage of AtomicInteger, which has additional overhead for the atomic get/set operations.
     * - Avoid checking if the checked block is opaque twice.
     * - Avoid a redundant block state lookup by re-using {@param fromState}
     *
     * Additionally this implements the cleanups discussed in MC-196542. In particular:
     * - The handling of source-skylight is removed and is now handled by ordinary propagation
     * - The handling of three-way propagations is removed, so the two provided positions must now always be adjacent
     *
     * The rest of the implementation has been otherwise copied from vanilla, but is optimized to avoid constantly
     * (un)packing coordinates and to use an optimized direction lookup function.
     *
     * @param fromState The re-usable block state at position {@param fromId}
     */
    @Override
    public int getPropagatedLevel(long fromId, BlockState fromState, long toId, int currentLevel, Direction dir) {
        if (toId == Long.MAX_VALUE) {
            return 15;
        } else if (fromId == Long.MAX_VALUE) {
            return 15; // MC-196524: Remove special handling of source-skylight
        } else if (currentLevel >= 15) {
            return currentLevel;
        }

        int toX = BlockPos.unpackLongX(toId);
        int toY = BlockPos.unpackLongY(toId);
        int toZ = BlockPos.unpackLongZ(toId);

        BlockState toState = this.getBlockStateForLighting(toX, toY, toZ);

        if (toState == null) {
            return 15;
        }

        int fromX = BlockPos.unpackLongX(fromId);
        int fromY = BlockPos.unpackLongY(fromId);
        int fromZ = BlockPos.unpackLongZ(fromId);

        if (fromState == null) {
            fromState = this.getBlockStateForLighting(fromX, fromY, fromZ);
        }

        if (dir == null) {
            // Calculate the direction the light update is propagating since it wasn't passed to us
            dir = DirectionHelper.getVecDirection(toX - fromX, toY - fromY, toZ - fromZ);

            if (dir == null) {
                return 15; // MC-196542: The provided positions should always be adjacent
            }
        }

        // Shape comparison checks are only meaningful if the blocks involved have non-empty shapes
        // If we're comparing between air blocks, this is meaningless
        if (toState != AIR_BLOCK || fromState != AIR_BLOCK) {
            VoxelShape toShape = this.getOpaqueShape(toState, toX, toY, toZ, dir.getOpposite());

            if (toShape != VoxelShapes.fullCube()) {
                VoxelShape fromShape = this.getOpaqueShape(fromState, fromX, fromY, fromZ, dir);

                if (LightUtil.unionCoversFullCube(fromShape, toShape)) {
                    return 15;
                }
            }
        }

        int out = this.getSubtractedLight(toState, toX, toY, toZ);

        // MC-196542: No special handling for source-skylight
        if (out == 0 && currentLevel == 0 && fromX == toX && fromZ == toZ && fromY > toY) {
            return 0;
        }

        return currentLevel + Math.max(1, out);
    }

    /**
     * A few key optimizations are made here, in particular:
     * - The code avoids un-packing coordinates as much as possible and stores the results into local variables.
     * - When necessary, coordinate re-packing is reduced to the minimum number of operations. Most of them can be reduced
     * to only updating the Y-coordinate versus re-computing the entire integer.
     * - Coordinate re-packing is removed where unnecessary (such as when only comparing the Y-coordinate of two positions)
     * - A special propagation method is used that allows the BlockState at {@param id} to be passed, allowing the code
     * which follows to simply re-use it instead of redundantly retrieving another block state.
     *
     * Additionally this implements the cleanups discussed in MC-196542. In particular:
     * - This always passes adjacent positions to {@link LevelPropagatorExtended#propagateLevel(long, BlockState, long, int, boolean, Direction)}
     * - This reduces map lookups in the skylight optimization code
     *
     * @reason Use faster implementation
     * @author JellySquid, PhiPro
     */
    @Override
    @Overwrite
    public void propagateLevel(long id, int targetLevel, boolean mergeAsMin) {
        int x = BlockPos.unpackLongX(id);
        int y = BlockPos.unpackLongY(id);
        int z = BlockPos.unpackLongZ(id);

        BlockState fromState = this.getBlockStateForLighting(x, y, z);

        int localX = ChunkSectionPos.getLocalCoord(x);
        int localY = ChunkSectionPos.getLocalCoord(y);
        int localZ = ChunkSectionPos.getLocalCoord(z);

        int chunkY = ChunkSectionPos.getSectionCoord(y);

        // Top Face
        if (localY != 15 || this.lightStorage.hasSection(ChunkSectionPos.asLong(ChunkSectionPos.getSectionCoord(x), chunkY + 1, ChunkSectionPos.getSectionCoord(z)))) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x, y + 1, z), targetLevel, mergeAsMin, Direction.UP);
        }

        int minChunkY = chunkY;

        // Bottom Face
        if (localY != 0) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x, y - 1, z), targetLevel, mergeAsMin, Direction.DOWN);
        } else {
            long chunkId = ChunkSectionPos.asLong(ChunkSectionPos.getSectionCoord(x), chunkY, ChunkSectionPos.getSectionCoord(z));

            // Skylight optimization: Try to find bottom-most non-empty chunk
            do {
                minChunkY--;
            } while (this.lightStorage.isAboveMinHeight(minChunkY) && !this.lightStorage.hasSection(ChunkSectionPosHelper.updateYLong(chunkId, minChunkY)));

            this.propagateLevel(BlockPos.asLong(x, (minChunkY << 4) + 16, z), AIR_BLOCK, BlockPos.asLong(x, (minChunkY << 4) + 15, z), targetLevel, mergeAsMin, Direction.DOWN);
        }

        // West Face
        if (localX != 0) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x - 1, y, z), targetLevel, mergeAsMin, Direction.WEST);
        } else {
            this.propagateNeighborHorizontal(id, fromState, targetLevel, mergeAsMin, chunkY, minChunkY, x, y, z, Direction.WEST);
        }

        // East Face
        if (localX != 15) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x + 1, y,  z), targetLevel, mergeAsMin, Direction.EAST);
        } else {
            this.propagateNeighborHorizontal(id, fromState, targetLevel, mergeAsMin, chunkY, minChunkY, x, y, z, Direction.EAST);
        }

        // North Face
        if (localZ != 0) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x, y, z - 1), targetLevel, mergeAsMin, Direction.NORTH);
        } else {
            this.propagateNeighborHorizontal(id, fromState, targetLevel, mergeAsMin, chunkY, minChunkY, x, y, z, Direction.NORTH);
        }

        // South Face
        if (localZ != 15) {
            this.propagateLevel(id, fromState, BlockPos.asLong(x, y, z + 1), targetLevel, mergeAsMin, Direction.SOUTH);
        } else {
            this.propagateNeighborHorizontal(id, fromState, targetLevel, mergeAsMin, chunkY, minChunkY, x, y, z, Direction.SOUTH);
        }
    }

    private void propagateNeighborHorizontal(long id, BlockState fromState, int targetLevel, boolean mergeAsMin, int chunkY, int minChunkY, int x, int y, int z, Direction dir) {
        int adjX = x + dir.getOffsetX();
        int adjZ = z + dir.getOffsetZ();

        long offsetChunkId = ChunkSectionPos.asLong(ChunkSectionPos.getSectionCoord(adjX), chunkY, ChunkSectionPos.getSectionCoord(adjZ));

        if (this.lightStorage.hasSection(offsetChunkId)) {
            this.propagateLevel(id, fromState, BlockPos.asLong(adjX, y, adjZ), targetLevel, mergeAsMin, dir);
        }

        // MC-196542: First iterate over sections to reduce map lookups
        for (int offsetChunkY = chunkY - 1; offsetChunkY > minChunkY; offsetChunkY--) {
            if (!this.lightStorage.hasSection(ChunkSectionPosHelper.updateYLong(offsetChunkId, offsetChunkY))) {
                continue;
            }

            int offsetChunkYBase = ChunkSectionPos.getBlockCoord(offsetChunkY);

            for (int adjY = offsetChunkYBase + 15; adjY >= offsetChunkYBase; adjY--) {
                long srcId = BlockPos.asLong(x, adjY, z);
                long offsetId = BlockPos.asLong(adjX, adjY, adjZ);

                // MC-196542: Pass adjacent source position
                this.propagateLevel(srcId, AIR_BLOCK, offsetId, targetLevel, mergeAsMin, dir);
            }
        }
    }

    @Unique
    private int getLightWithoutLightmap(final long blockPos) {
        return 15 - ((LightStorageAccess) this.lightStorage).getLightWithoutLightmap(blockPos);
    }

    /**
     * This implements the cleanups discussed in MC-196542. In particular:
     * - The special handling of source-skylight is removed and is instead taken care of via ordinary propagation
     * - The logic for looking up light values without associated lightmap is now uniformly applied to all directions and moved to {@link LightStorageAccess#getLightWithoutLightmap(long)}
     * - This passes adjacent positions to {@link #getPropagatedLevel(long, long, int)}
     * - This fixes a bug when propagating direct skylight from horizontal neighbors
     *
     * @author PhiPro
     * @reason Implement MC-196542
     */
    @Overwrite
    public int recalculateLevel(long id, long excludedId, int maxLevel) {
        int currentLevel = maxLevel;

        // MC-196542: Remove special handling of source-skylight

        long chunkId = ChunkSectionPos.fromBlockPos(id);
        ChunkNibbleArray lightmap = ((LightStorageAccess) this.lightStorage).callGetLightSection(chunkId, true);

        for(Direction direction : DIRECTIONS) {
            long adjId = BlockPos.offset(id, direction);

            if (adjId == excludedId) {
                continue;
            }

            long adjChunkId = ChunkSectionPos.fromBlockPos(adjId);

            ChunkNibbleArray adjLightmap;

            if (chunkId == adjChunkId) {
                adjLightmap = lightmap;
            } else {
                adjLightmap = ((LightStorageAccess) this.lightStorage).callGetLightSection(adjChunkId, true);
            }

            final int adjLevel;

            if (adjLightmap == null) {
                // MC-196542: Apply this lookup uniformly to all directions and move it into LightStorage
                adjLevel = this.getLightWithoutLightmap(adjId);
            } else {
                adjLevel = this.getCurrentLevelFromSection(adjLightmap, adjId);
            }

            // MC-196542: Pass adjacent source position
            int propagatedLevel = this.getPropagatedLevel(adjId, null, id, adjLevel, direction.getOpposite());

            if (currentLevel > propagatedLevel) {
                currentLevel = propagatedLevel;
            }

            if (currentLevel == 0) {
                return currentLevel;
            }
        }

        return currentLevel;
    }

    /**
     * @author PhiPro
     * @reason MC-196542: There is no need to reset any level other than the directly requested position.
     */
    @Overwrite
    public void resetLevel(long id) {
        super.resetLevel(id);
    }
}
