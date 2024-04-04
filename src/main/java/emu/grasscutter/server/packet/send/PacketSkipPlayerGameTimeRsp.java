package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BaseTypedPacket;
import messages.player.SkipPlayerGameTimeRsp;

public class PacketSkipPlayerGameTimeRsp extends BaseTypedPacket<SkipPlayerGameTimeRsp> {

    public PacketSkipPlayerGameTimeRsp(Player player, int gameTime, int clientGameTime, boolean result) {
        super( new SkipPlayerGameTimeRsp());

        //TODO handle result
        proto.setGameTime(gameTime);
        proto.setClientGameTime(clientGameTime);
    }
}