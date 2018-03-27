package tick.threading.chunk;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.IChunkGenerator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadedChunkProvider {
    private final ExecutorService chunkPool = Executors.newFixedThreadPool(10);
    private final IChunkGenerator generatorInstance;
    private final IChunkLoader chunkLoader;
    private final ArrayList<Chunk> chunks;
    private final BlockingQueue<ChunkPos> saveQueue;
    private final Thread saveThread;
    private final World world;

    public ThreadedChunkProvider(IChunkGenerator generator, IChunkLoader loader, World world) {
        this.generatorInstance = generator;
        this.chunkLoader = loader;
        this.saveQueue = new ArrayBlockingQueue<>(300);
        chunks = new ArrayList<>();
        this.world = world;
        saveThread = new Thread(new SaveRunnable(this.chunkLoader, this.world, this));
        saveThread.start();
    }

    private void addChunk(Chunk chunk) {
        synchronized (this.chunks) {
            this.chunks.add(chunk);
        }
    }

    private void removeChunk(Chunk chunk) {
        synchronized (this.chunks) {
            this.chunks.remove(chunk);
        }
    }

    public Chunk getChunkAt(int x, int z) {
        synchronized (this.chunks) {
            Iterator<Chunk> iterator = chunks.iterator();
            while (iterator.hasNext()) {
                Chunk iteratedChunk = iterator.next();
                if ((iteratedChunk.x == x) && (iteratedChunk.z == z)) {
                    return iteratedChunk;
                }
            }
            return null;
        }
    }

    public void loadChunkAt(int x, int z) {
        LoadRunnable loadRunnable = new LoadRunnable(x, z, this.chunkLoader, this.world, this);
        this.chunkPool.submit(loadRunnable);
    }

    public boolean saveChunkAt(int x, int z) {
        return this.saveQueue.offer(new ChunkPos(x, z));
    }

    private class LoadRunnable implements Runnable {
        int x;
        int z;
        IChunkLoader loader;
        World world;
        ThreadedChunkProvider provider;

        LoadRunnable(int x, int z, IChunkLoader loader, World world, ThreadedChunkProvider provider) {
            this.x = x;
            this.z = z;
            this.loader = loader;
            this.world = world;
        }

        @Override
        public void run() {
            try {
                Chunk loaded = chunkLoader.loadChunk(world, x, z);
                this.provider.addChunk(loaded);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class SaveRunnable implements Runnable {
        IChunkLoader loader;
        World world;
        ThreadedChunkProvider provider;

        SaveRunnable(IChunkLoader loader, World world, ThreadedChunkProvider provider) {
            this.loader = loader;
            this.world = world;
            this.provider = provider;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    ChunkPos pos = provider.saveQueue.take();
                    Chunk toSave = provider.getChunkAt(pos.x, pos.z);
                    loader.saveChunk(world, toSave);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                } catch (MinecraftException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }
    }
}
