package mcjty.lib.network;

import io.netty.buffer.ByteBuf;
import mcjty.lib.McJtyLib;
import mcjty.lib.thirteen.Context;
import mcjty.lib.tileentity.GenericTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Request information from the tile entity which is needed for the GUI
 */
public class PacketSendGuiData implements IMessage {
    private int dimId;
    private BlockPos pos;
    private Object[] data;

    private static class Entry<T> {
        private final Class<T> clazz;
        private final Function<ByteBuf, ? extends T> func;
        private final Consumer<Pair<ByteBuf, ? extends T>> consumer;

        private Entry(Class<T> clazz, Function<ByteBuf, ? extends T> func, Consumer<Pair<ByteBuf, ? extends T>> consumer) {
            this.clazz = clazz;
            this.func = func;
            this.consumer = consumer;
        }

        public static <T> Entry<T> of(Class<T> clazz, Function<ByteBuf, ? extends T> func, Consumer<Pair<ByteBuf, ? extends T>> consumer) {
            return new Entry<>(clazz, func, consumer);
        }

        public boolean match(Object o) {
            return clazz.isInstance(o);
        }

        public T cast(Object o) {
            return clazz.cast(o);
        }
    }


    private static final Map<Integer,Entry<?>> CLASS_MAP = new HashMap<>();
    static {
        int id = 0;
        CLASS_MAP.put(id++, Entry.of(Integer.class, ByteBuf::readInt, p -> p.getLeft().writeInt(p.getRight())));
        CLASS_MAP.put(id++, Entry.of(String.class, NetworkTools::readString, p -> NetworkTools.writeString(p.getLeft(), p.getRight())));
        CLASS_MAP.put(id++, Entry.of(Float.class, ByteBuf::readFloat, p -> p.getLeft().writeFloat(p.getRight())));
        CLASS_MAP.put(id++, Entry.of(Boolean.class, ByteBuf::readBoolean, p -> p.getLeft().writeBoolean(p.getRight())));
        CLASS_MAP.put(id++, Entry.of(Byte.class, ByteBuf::readByte, p -> p.getLeft().writeByte(p.getRight())));
        CLASS_MAP.put(id++, Entry.of(Long.class, ByteBuf::readLong, p -> p.getLeft().writeLong(p.getRight())));
        CLASS_MAP.put(id++, Entry.of(BlockPos.class, NetworkTools::readPos, p -> NetworkTools.writePos(p.getLeft(), p.getRight())));
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimId = buf.readInt();
        pos = NetworkTools.readPos(buf);
        int size = buf.readShort();
        data = new Object[size];
        for (int i = 0 ; i < size ; i++) {
            int type = buf.readByte();
            data[i] = CLASS_MAP.get(type).func.apply(buf);
        }
    }

    private static <T> void acceptCasted(Entry<T> triple, ByteBuf buf, Object o) {
        triple.consumer.accept(Pair.of(buf, triple.cast(o)));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimId);
        NetworkTools.writePos(buf, pos);
        buf.writeShort(data.length);
        for (Object o : data) {
            boolean ok = false;
            for (Map.Entry<Integer, Entry<?>> entry : CLASS_MAP.entrySet()) {
                Entry<?> triple = entry.getValue();
                if (triple.match(o)) {
                    buf.writeByte(entry.getKey());
                    acceptCasted(triple, buf, o);
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new RuntimeException("Unsupported type in getDataForGUI!");
            }
        }

    }

    public PacketSendGuiData() {
    }

    public PacketSendGuiData(ByteBuf buf) {
        fromBytes(buf);
    }

    public PacketSendGuiData(World world, BlockPos pos) {
        this.dimId = world.provider.getDimension();
        this.pos = pos;
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof GenericTileEntity) {
            GenericTileEntity genericTileEntity = (GenericTileEntity) te;
            data = genericTileEntity.getDataForGUI();
        } else {
            data = new Object[0];
        }
    }

    public void handle(Supplier<Context> supplier) {
        Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            World world = McJtyLib.proxy.getClientWorld();
            if (world.provider.getDimension() == dimId) {
                TileEntity te = world.getTileEntity(pos);
                if (te instanceof GenericTileEntity) {
                    GenericTileEntity tileEntity = (GenericTileEntity) te;
                    tileEntity.syncDataForGUI(data);
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
