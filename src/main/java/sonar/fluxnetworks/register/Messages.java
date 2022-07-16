package sonar.fluxnetworks.register;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.player.Player;
import sonar.fluxnetworks.FluxNetworks;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.api.device.IFluxDevice;
import sonar.fluxnetworks.api.network.SecurityLevel;
import sonar.fluxnetworks.common.capability.FluxPlayer;
import sonar.fluxnetworks.common.connection.*;
import sonar.fluxnetworks.common.device.TileFluxDevice;
import sonar.fluxnetworks.common.item.ItemAdminConfigurator;
import sonar.fluxnetworks.common.util.FluxUtils;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.function.Supplier;

import static sonar.fluxnetworks.register.Channel.sChannel;

/**
 * Network messages, TCP protocol. This class contains common messages, S2C message specs
 * and C2S message handling.
 * <p>
 * Security check is necessary on the server side. However, due to network latency,
 * players cannot be kicked out unless the packet is <em>seriously illegal</em>.
 * <p>
 * Some terms:
 * <ul>
 *   <li><b>Protocol</b>: controls message codec and handling, if some of them changed, then protocol
 *   needs to update.</li>
 *   <li><b>Index</b>: identifies message body, unsigned short, 0-based indexing, must be sequential
 *   for table lookup (switch statement), server-to-client and client-to-server are independent.</li>
 *   <li><b>Token</b>: the container id, unsigned byte, generated by Minecraft, ranged from 1 to 100.
 *   Container menu is required for C/S communication, if the initiator (the server side) is destroyed,
 *   then the token is expired.</li>
 *   <li><b>Response</b>: the return code sent by the server to the client to respond to a client request.
 *   There's a key used to identify the request, generally, it is the same as the message index.
 *   That is, modal, client is waiting for the server.</li>
 * </ul>
 *
 * @author BloCamLimb
 * @since 7.0
 */
@ParametersAreNonnullByDefault
public class Messages {

    /**
     * C->S message indices, must be sequential, 0-based indexing
     */
    static final int C2S_DEVICE_BUFFER = 0;
    static final int C2S_SUPER_ADMIN = 1;
    static final int C2S_CREATE_NETWORK = 2;
    static final int C2S_DELETE_NETWORK = 3;
    static final int C2S_EDIT_TILE = 4;
    static final int C2S_TILE_NETWORK = 5;
    static final int C2S_EDIT_ITEM = 6;
    static final int C2S_ITEM_NETWORK = 7;
    static final int C2S_EDIT_MEMBER = 8;
    static final int C2S_EDIT_NETWORK = 9;
    static final int C2S_EDIT_CONNECTION = 10;
    static final int C2S_UPDATE_NETWORK = 11;
    static final int C2S_WIRELESS_MODE = 12;
    static final int C2S_DISCONNECT = 13;
    static final int C2S_UPDATE_CONNECTIONS = 14;
    static final int C2S_TRACK_MEMBERS = 15;
    static final int C2S_TRACK_CONNECTIONS = 16;
    static final int C2S_TRACK_STATISTICS = 17;

    /**
     * S->C message indices, must be sequential, 0-based indexing
     */
    static final int S2C_DEVICE_BUFFER = 0;
    static final int S2C_RESPONSE = 1;
    static final int S2C_CAPABILITY = 2;
    static final int S2C_UPDATE_NETWORK = 3;
    static final int S2C_DELETE_NETWORK = 4;
    static final int S2C_UPDATE_CONNECTIONS = 5;
    static final int S2C_UPDATE_MEMBERS = 6;

    /**
     * Byte stream.
     *
     * @param device the block entity created by server
     * @param type   for example, {@link FluxConstants#DEVICE_S2C_GUI_SYNC}
     * @return dispatcher
     */
    @Nonnull
    public static FriendlyByteBuf deviceBuffer(TileFluxDevice device, byte type) {
        assert type < 0; // S2C negative
        var buf = Channel.buffer(S2C_DEVICE_BUFFER);
        buf.writeBlockPos(device.getBlockPos());
        buf.writeByte(type);
        device.writePacket(buf, type);
        return buf;
    }

    /**
     * Response to client.
     *
     * @param token the container id
     * @param key   the request key
     * @param code  the response code
     */
    private static void response(int token, int key, int code, Player player) {
        var buf = Channel.buffer(S2C_RESPONSE);
        buf.writeByte(token);
        buf.writeShort(key);
        buf.writeByte(code);
        sChannel.sendToPlayer(buf, player);
    }

    /**
     * Update player's capability.
     */
    public static void capability(Player player) {
        var buf = Channel.buffer(S2C_CAPABILITY);
        FluxPlayer fluxPlayer = FluxUtils.get(player, FluxPlayer.FLUX_PLAYER);
        if (fluxPlayer != null) {
            buf.writeBoolean(FluxPlayer.isPlayerSuperAdmin(player));
            buf.writeInt(fluxPlayer.getWirelessMode());
            buf.writeVarInt(fluxPlayer.getWirelessNetwork());
            sChannel.sendToPlayer(buf, player);
        }
    }

    /**
     * Variation of {@link #updateNetwork(Collection, byte)} that updates only one network.
     */
    @Nonnull
    public static FriendlyByteBuf updateNetwork(FluxNetwork network, byte type) {
        var buf = Channel.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(1); // size
        buf.writeVarInt(network.getNetworkID());
        final var tag = new CompoundTag();
        network.writeCustomTag(tag, type);
        buf.writeNbt(tag);
        return buf;
    }

    @Nonnull
    private static FriendlyByteBuf updateConnections(FluxNetwork network, List<CompoundTag> tags) {
        var buf = Channel.buffer(S2C_UPDATE_CONNECTIONS);
        buf.writeVarInt(network.getNetworkID());
        buf.writeVarInt(tags.size());
        for (CompoundTag tag : tags) {
            buf.writeNbt(tag);
        }
        return buf;
    }

    @Nonnull
    public static FriendlyByteBuf updateNetwork(Collection<FluxNetwork> networks, byte type) {
        var buf = Channel.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(networks.size());
        for (var network : networks) {
            buf.writeVarInt(network.getNetworkID());
            final var tag = new CompoundTag();
            network.writeCustomTag(tag, type);
            buf.writeNbt(tag);
        }
        return buf;
    }

    @Nonnull
    private static FriendlyByteBuf updateNetwork(int[] networkIDs, byte type) {
        var buf = Channel.buffer(S2C_UPDATE_NETWORK);
        buf.writeByte(type);
        buf.writeVarInt(networkIDs.length);
        for (var networkID : networkIDs) {
            buf.writeVarInt(networkID);
            final var tag = new CompoundTag();
            FluxNetworkData.getNetwork(networkID).writeCustomTag(tag, type);
            buf.writeNbt(tag);
        }
        return buf;
    }

    /**
     * Notify all clients that a network was deleted.
     */
    public static void deleteNetwork(int id) {
        var buf = Channel.buffer(S2C_DELETE_NETWORK);
        buf.writeVarInt(id);
        sChannel.sendToAll(buf);
    }

    static void msg(short index, FriendlyByteBuf payload, Supplier<ServerPlayer> player) {
        MinecraftServer server = player.get().getLevel().getServer();
        switch (index) {
            case C2S_DEVICE_BUFFER -> onDeviceBuffer(payload, player, server);
            case C2S_SUPER_ADMIN -> onSuperAdmin(payload, player, server);
            case C2S_CREATE_NETWORK -> onCreateNetwork(payload, player, server);
            case C2S_DELETE_NETWORK -> onDeleteNetwork(payload, player, server);
            case C2S_EDIT_TILE -> onEditTile(payload, player, server);
            case C2S_TILE_NETWORK -> onTileNetwork(payload, player, server);
            case C2S_EDIT_MEMBER -> onEditMember(payload, player, server);
            case C2S_EDIT_NETWORK -> onEditNetwork(payload, player, server);
            case C2S_EDIT_CONNECTION -> onEditConnection(payload, player, server);
            case C2S_UPDATE_NETWORK -> onUpdateNetwork(payload, player, server);
            case C2S_WIRELESS_MODE -> onWirelessMode(payload, player, server);
            case C2S_DISCONNECT -> onDisconnect(payload, player, server);
            case C2S_UPDATE_CONNECTIONS -> onUpdateConnections(payload, player, server);
            default -> kick(player.get(), new RuntimeException("Unidentified message index " + index));
        }
    }

    private static void kick(ServerPlayer p, RuntimeException e) {
        if (p.server.isDedicatedServer()) {
            p.connection.disconnect(new TranslatableComponent("multiplayer.disconnect.invalid_packet"));
            FluxNetworks.LOGGER.info("Received invalid packet from player {}", p.getGameProfile().getName(), e);
        } else {
            FluxNetworks.LOGGER.info("Received invalid packet", e);
        }
    }

    private static void consume(FriendlyByteBuf payload) {
        if (payload.isReadable()) {
            throw new DecoderException("Payload is not fully consumed");
        }
    }

    private static void onDeviceBuffer(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                       BlockableEventLoop<?> looper) {
        payload.retain();
        looper.execute(() -> {
            ServerPlayer p = player.get();
            try {
                if (p != null && p.level.getBlockEntity(payload.readBlockPos()) instanceof TileFluxDevice e) {
                    if (e.canPlayerAccess(p)) {
                        byte id = payload.readByte();
                        if (id > 0) {
                            e.readPacket(payload, id);
                        } else {
                            throw new IllegalArgumentException();
                        }
                        consume(payload);
                    }
                }
            } catch (RuntimeException e) {
                kick(p, e);
            }
            payload.release();
        });
    }

    private static void onSuperAdmin(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final boolean enable = payload.readBoolean();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxPlayer fp = FluxUtils.get(p, FluxPlayer.FLUX_PLAYER);
            if (fp != null) {
                if (fp.isSuperAdmin() || FluxPlayer.canActivateSuperAdmin(p)) {
                    fp.setSuperAdmin(enable);
                    capability(p);
                } else {
                    response(token, 0, FluxConstants.RESPONSE_REJECT, p);
                }
            } else {
                response(token, 0, FluxConstants.RESPONSE_INVALID_USER, p);
            }
        });
    }

    private static void onEditTile(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                   BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final BlockPos pos = payload.readBlockPos();
        final CompoundTag tag = payload.readNbt();

        // validate
        consume(payload);
        Objects.requireNonNull(tag);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            boolean reject = p.containerMenu.containerId != token || !(p.containerMenu instanceof FluxMenu);
            if (reject) {
                response(token, FluxConstants.REQUEST_EDIT_TILE, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            try {
                if (p.level.isLoaded(pos) &&
                        p.level.getBlockEntity(pos) instanceof TileFluxDevice e &&
                        e.canPlayerAccess(p)) {
                    e.readCustomTag(tag, FluxConstants.NBT_TILE_SETTINGS);
                } else {
                    response(token, FluxConstants.REQUEST_EDIT_TILE, FluxConstants.RESPONSE_REJECT, p);
                }
            } catch (RuntimeException e) {
                kick(p, e);
            }
        });
    }

    private static void onCreateNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final String name = payload.readUtf(256);
        final int color = payload.readInt();
        final SecurityLevel security = SecurityLevel.fromKey(payload.readByte());
        final String password = security == SecurityLevel.ENCRYPTED ? payload.readUtf(256) : "";

        // validate
        consume(payload);
        if (FluxUtils.isBadNetworkName(name)) {
            throw new IllegalArgumentException("Invalid network name: " + name);
        }
        if (security == SecurityLevel.ENCRYPTED && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            boolean reject = p.containerMenu.containerId != token || !(p.containerMenu instanceof FluxMenu);
            if (reject) {
                response(token, FluxConstants.REQUEST_CREATE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            if (FluxNetworkData.getInstance().createNetwork(p, name, color, security, password) != null) {
                response(token, FluxConstants.REQUEST_CREATE_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
            } else {
                response(token, FluxConstants.REQUEST_CREATE_NETWORK, FluxConstants.RESPONSE_NO_SPACE, p);
            }
        });
    }

    private static void onDeleteNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            if (network.isValid()) {
                if (network.getPlayerAccess(p).canDelete()) {
                    FluxNetworkData.getInstance().deleteNetwork(network);
                    response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
                } else {
                    response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_NO_OWNER, p);
                }
            } else {
                response(token, FluxConstants.REQUEST_DELETE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }

    private static void onTileNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                      BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final BlockPos pos = payload.readBlockPos();
        final int networkID = payload.readVarInt();
        final String password = payload.readUtf(256);

        // validate
        consume(payload);
        if (!password.isEmpty() && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            if (p.level.getBlockEntity(pos) instanceof TileFluxDevice e) {
                if (e.getNetworkID() == networkID) {
                    return;
                }
                if (!e.canPlayerAccess(p)) {
                    response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
                    return;
                }
                final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
                if (e.getDeviceType().isController() && network.getLogicalDevices(FluxNetwork.CONTROLLER).size() > 0) {
                    response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_HAS_CONTROLLER, p);
                    return;
                }
                // we can connect to an invalid network (i.e. disconnect)
                if (!network.isValid() || network.canPlayerAccess(p, password)) {
                    if (network.isValid()) {
                        // silently ignored on disconnect
                        e.setOwnerUUID(p.getUUID());
                    }
                    e.connect(network);
                    response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
                    return;
                }
                if (password.isEmpty()) {
                    response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_REQUIRE_PASSWORD, p);
                } else {
                    response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_INVALID_PASSWORD, p);
                }
            } else {
                response(token, FluxConstants.REQUEST_TILE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }

    private static void onEditNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                      BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final String name = payload.readUtf(256);
        final int color = payload.readInt();
        final SecurityLevel security = SecurityLevel.fromKey(payload.readByte());
        final String password = security == SecurityLevel.ENCRYPTED ? payload.readUtf(256) : "";

        // validate
        consume(payload);
        if (FluxUtils.isBadNetworkName(name)) {
            throw new IllegalArgumentException("Invalid network name: " + name);
        }
        if (!password.isEmpty() && FluxUtils.isBadPassword(password)) {
            throw new IllegalArgumentException("Invalid network password: " + password);
        }

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            boolean reject = checkTokenFailed(token, p, network);
            if (reject) {
                response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            assert network.isValid();
            if (network.getPlayerAccess(p).canEdit()) {
                boolean changed = network.setNetworkName(name);
                if (network.setNetworkColor(color)) {
                    // update renderer
                    network.getLogicalDevices(FluxNetwork.ANY).forEach(TileFluxDevice::sendBlockUpdate);
                    changed = true;
                }
                changed |= network.setSecurityLevel(security);
                if (!password.isEmpty()) {
                    ((ServerFluxNetwork) network).setPassword(password);
                    // silently changed
                }
                if (changed) {
                    sChannel.sendToAll(updateNetwork(network, FluxConstants.NBT_NET_BASIC));
                }
                response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_SUCCESS, p);
            } else {
                response(token, FluxConstants.REQUEST_EDIT_NETWORK, FluxConstants.RESPONSE_NO_ADMIN, p);
            }
        });
    }

    private static void onUpdateNetwork(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                        BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int size = payload.readVarInt();
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        final int[] networkIDs = new int[size];
        for (int i = 0; i < size; i++) {
            networkIDs[i] = payload.readVarInt();
        }
        final byte type = payload.readByte();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            boolean reject = true;
            if (p.containerMenu.containerId == token && p.containerMenu instanceof FluxMenu menu) {
                if (FluxPlayer.isPlayerSuperAdmin(p)) {
                    reject = false;
                } else if (networkIDs.length == 1) {
                    // non-super admin can only request one network update
                    int networkID = networkIDs[0];
                    final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
                    // admin configurator is decoration, check access permission
                    if (!(menu.mProvider instanceof ItemAdminConfigurator.Provider)) {
                        // in this case, player can access this BlockEntity or FluxConfigurator
                        if (network.isValid() && menu.mProvider.getNetworkID() == networkID) {
                            reject = false;
                        }
                    } else {
                        if (network.isValid() && network.canPlayerAccess(p, "")) {
                            reject = false;
                        }
                    }
                }
            }
            if (reject) {
                response(token, FluxConstants.REQUEST_UPDATE_NETWORK, FluxConstants.RESPONSE_REJECT, p);
            } else {
                // this packet always triggers an event, so no response
                sChannel.sendToPlayer(updateNetwork(networkIDs, type), p);
            }
        });
    }

    private static void onEditMember(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final UUID targetUUID = payload.readUUID();
        final byte type = payload.readByte();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            boolean reject = checkTokenFailed(token, p, network);
            if (reject) {
                response(token, FluxConstants.REQUEST_EDIT_MEMBER, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            assert network.isValid();
            int code = network.changeMembership(p, targetUUID, type);
            if (code == FluxConstants.RESPONSE_SUCCESS) {
                sChannel.sendToPlayer(updateNetwork(network, FluxConstants.NBT_NET_MEMBERS), p);
            }
            response(token, FluxConstants.REQUEST_EDIT_MEMBER, code, p);
        });
    }

    private static void onEditConnection(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                         BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final int size = payload.readVarInt();
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        final List<GlobalPos> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(FluxUtils.readGlobalPos(payload));
        }
        final CompoundTag tag = payload.readNbt();

        // validate
        consume(payload);
        Objects.requireNonNull(tag);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            boolean reject = checkTokenFailed(token, p, network);
            if (reject) {
                response(token, FluxConstants.REQUEST_EDIT_CONNECTION, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            assert network.isValid();
            if (network.getPlayerAccess(p).canEdit()) {
                try {
                    for (GlobalPos pos : list) {
                        IFluxDevice f = network.getConnectionByPos(pos);
                        if (f instanceof TileFluxDevice e) {
                            e.readCustomTag(tag, FluxConstants.NBT_TILE_SETTINGS);
                        }
                    }
                } catch (RuntimeException e) {
                    kick(p, e);
                    return;
                }
                response(token, FluxConstants.REQUEST_EDIT_CONNECTION, FluxConstants.RESPONSE_SUCCESS, p);
            } else {
                response(token, FluxConstants.REQUEST_EDIT_CONNECTION, FluxConstants.RESPONSE_NO_ADMIN, p);
            }
        });
    }

    private static boolean checkTokenFailed(int token, Player p, FluxNetwork network) {
        if (!network.isValid()) {
            return true;
        }
        if (p.containerMenu.containerId == token && p.containerMenu instanceof FluxMenu menu) {
            if (FluxPlayer.isPlayerSuperAdmin(p)) {
                return false;
            } else {
                if (!(menu.mProvider instanceof ItemAdminConfigurator.Provider)) {
                    return menu.mProvider.getNetworkID() != network.getNetworkID() &&
                            !network.canPlayerAccess(p, "");
                } else {
                    return !network.canPlayerAccess(p, "");
                }
            }
        }
        // reject
        return true;
    }

    private static void onWirelessMode(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                       BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int wirelessMode = payload.readInt();
        final int wirelessNetwork = payload.readVarInt();

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxPlayer fp = FluxUtils.get(p, FluxPlayer.FLUX_PLAYER);
            if (fp != null) {
                final FluxNetwork network = FluxNetworkData.getNetwork(wirelessNetwork);
                // allow set to invalid
                boolean reject = network.isValid() && checkTokenFailed(token, p, network);
                if (reject) {
                    response(token, 0, FluxConstants.RESPONSE_REJECT, p);
                    return;
                }
                fp.setWirelessMode(wirelessMode);
                fp.setWirelessNetwork(wirelessNetwork);
                capability(p);
            } else {
                response(token, 0, FluxConstants.RESPONSE_INVALID_USER, p);
            }
        });
    }

    private static void onDisconnect(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                     BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final int size = payload.readVarInt();
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        final List<GlobalPos> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(FluxUtils.readGlobalPos(payload));
        }

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            boolean reject = checkTokenFailed(token, p, network);
            if (reject) {
                response(token, FluxConstants.REQUEST_DISCONNECT, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            assert network.isValid();
            if (network.getPlayerAccess(p).canEdit()) {
                for (GlobalPos pos : list) {
                    IFluxDevice f = network.getConnectionByPos(pos);
                    if (f instanceof TileFluxDevice e) {
                        e.disconnect();
                    }
                }
                response(token, FluxConstants.REQUEST_DISCONNECT, FluxConstants.RESPONSE_SUCCESS, p);
            } else {
                response(token, FluxConstants.REQUEST_DISCONNECT, FluxConstants.RESPONSE_NO_ADMIN, p);
            }
        });
    }

    private static void onUpdateConnections(FriendlyByteBuf payload, Supplier<ServerPlayer> player,
                                            BlockableEventLoop<?> looper) {
        // decode
        final int token = payload.readByte();
        final int networkID = payload.readVarInt();
        final int size = payload.readVarInt();
        if (size <= 0 || size > 7) {
            throw new IllegalArgumentException();
        }
        final List<GlobalPos> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(FluxUtils.readGlobalPos(payload));
        }

        // validate
        consume(payload);

        looper.execute(() -> {
            final ServerPlayer p = player.get();
            if (p == null) {
                return;
            }
            final FluxNetwork network = FluxNetworkData.getNetwork(networkID);
            boolean reject = checkTokenFailed(token, p, network);
            if (reject) {
                response(token, FluxConstants.REQUEST_UPDATE_CONNECTION, FluxConstants.RESPONSE_REJECT, p);
                return;
            }
            assert network.isValid();
            if (network.canPlayerAccess(p, "")) {
                List<CompoundTag> tags = new ArrayList<>();
                for (GlobalPos pos : list) {
                    IFluxDevice f = network.getConnectionByPos(pos);
                    if (f != null) {
                        CompoundTag subTag = new CompoundTag();
                        f.writeCustomTag(subTag, FluxConstants.NBT_PHANTOM_UPDATE);
                        tags.add(subTag);
                    }
                }
                // this packet always triggers an event, so no response
                sChannel.sendToPlayer(updateConnections(network, tags), p);
            } else {
                response(token, FluxConstants.REQUEST_UPDATE_CONNECTION, FluxConstants.RESPONSE_REJECT, p);
            }
        });
    }
}
