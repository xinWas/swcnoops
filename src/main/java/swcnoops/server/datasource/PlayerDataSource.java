package swcnoops.server.datasource;

import swcnoops.server.commands.guild.response.SquadResult;
import swcnoops.server.session.PlayerSession;

public interface PlayerDataSource {
    Player loadPlayer(String playerId);
    void initOnStartup();
    void savePlayerName(String playerId, String playerName);

    PlayerSettings loadPlayerSettings(String playerId);

    void savePlayerSession(PlayerSession playerSession);

    void savePlayerSessions(PlayerSession playerSession, PlayerSession recipientPlayerSession);

    void newPlayer(String playerId, String secret);

    void newGuild(String playerId, SquadResult squadResult);

    GuildSettings loadGuildSettings(String guildId);

    void editGuild(String guildId, String description, String icon, Integer minScoreAtEnrollment, boolean openEnrollment);
}
