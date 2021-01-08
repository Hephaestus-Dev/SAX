package dev.hephaestus.sax.server;

import dev.hephaestus.sax.util.FastCaster;
import dev.hephaestus.sax.util.OreChunk;
import net.minecraft.block.Block;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class DeObfuscator {
    private final ServerPlayerEntity player;
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final Vec3d startPos = new Vec3d(0, 0, 0);
    private final ThreadLocal<Vec3d> endPos = ThreadLocal.withInitial(() -> new Vec3d(0, 0, 0));
    private final Map<BlockPos, Boolean> revealed = new ConcurrentHashMap<>();

    public DeObfuscator(ServerPlayerEntity player) {
        this.player = player;

        int s = Config.CHUNK_RADIUS * Config.CHUNK_RADIUS;

        this.executor = new ThreadPoolExecutor(s, s,
                0, TimeUnit.MILLISECONDS,
                this.taskQueue,
                (runnable) -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    return thread;
        });
    }


    public void tick() {
        if (this.taskQueue.isEmpty()) {
            this.action();
        }
    }

    private void action() {
        set(this.player.getCameraPosVec(1F), this.startPos);

        int chunkX = this.player.chunkX;
        int chunkZ = this.player.chunkZ;

        ServerWorld world = this.player.getServerWorld();
        ServerChunkManager chunkManager = world.getChunkManager();

        for (int x = chunkX - Config.CHUNK_RADIUS; x < chunkX + Config.CHUNK_RADIUS; ++x) {
            for (int z = chunkZ - Config.CHUNK_RADIUS; z < chunkZ + Config.CHUNK_RADIUS; ++z) {
                int finalX = x, finalZ = z;
                this.executor.execute(() -> {
                    BlockView blockView = chunkManager.getChunk(finalX, finalZ);

                    if (!(blockView instanceof WorldChunk)) return;

                    OreChunk oreChunkhunk = (OreChunk) blockView;

                    Collection<BlockPos> positions = oreChunkhunk.sax_getObfuscatedBlocks();

                    for (BlockPos pos : positions) {
                        Block block = oreChunkhunk.getBlockState(pos).getBlock();


                        if (Config.HIDDEN.containsKey(block)) {
                            if (this.traceForBlock(pos)) {
                                this.sendBlockUpdate(pos, this.player.networkHandler.connection::send);
                            }
                        }
                    }
                });
            }
        }
    }

    private void sendBlockUpdate(BlockPos pos, Consumer<BlockUpdateS2CPacket> consumer) {
        if (this.player.getServer() != null && !this.revealed.containsKey(pos)) {
            this.player.getServer().execute(() -> consumer.accept(new BlockUpdateS2CPacket(this.player.world, pos)));
            this.revealed.put(pos, true);
        }
    }

    private boolean traceForBlock(Vec3i target) {
        Vec3d pos = this.endPos.get();

        // We're checking all eight corners of our block
        for (byte dX = 0; dX <= 1; ++dX) {
            for (byte dY = 0; dY <= 1; ++dY) {
                for (byte dZ = 0; dZ <= 1; ++dZ) {
                    // We want to avoid creating a ton of new vectors.
                    pos.x = target.getX() + dX;
                    pos.y = target.getY() + dY;
                    pos.z = target.getZ() + dZ;

                    if (FastCaster.fastcast(this.player.getServerWorld(), this.startPos, pos, target)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static void set(Vec3d from, Vec3d to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
    }
}
