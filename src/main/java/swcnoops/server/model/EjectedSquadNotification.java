package swcnoops.server.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class EjectedSquadNotification extends SquadNotification {

    public EjectedSquadNotification(String guildId, String guildName, String message, String name,
                                    String playerId, SquadMsgType type)
    {
        super(guildId, guildName, message, name, playerId, type);
    }

    @JsonIgnore
    @Override
    public String getPlayerId() {
        return super.getPlayerId();
    }
}
