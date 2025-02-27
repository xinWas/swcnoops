package swcnoops.server.session;

import swcnoops.server.ServiceFactory;
import swcnoops.server.datasource.AttackDetail;
import swcnoops.server.datasource.DefendingWarParticipant;
import swcnoops.server.datasource.War;
import swcnoops.server.datasource.WarNotification;
import swcnoops.server.model.*;

import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static swcnoops.server.session.NotificationFactory.createNotification;

public class WarSessionImpl implements WarSession {
    final private String warId;
    private final GuildSession squadA;
    private final GuildSession squadB;
    private War war;
    private long dirtyTime;
    private long lastLoadedWarTime;
    private Lock guildGetLock = new ReentrantLock();
    private Lock warLock = new ReentrantLock();

    public WarSessionImpl(String warId) {
        this.warId = warId;
        this.war = ServiceFactory.instance().getPlayerDatasource().getWar(warId);
        this.lastLoadedWarTime = System.currentTimeMillis();
        this.squadA = ServiceFactory.instance().getSessionManager().getGuildSession(war.getSquadIdA());
        this.squadB = ServiceFactory.instance().getSessionManager().getGuildSession(war.getSquadIdB());
    }

    @Override
    public String getWarId() {
        return warId;
    }

    @Override
    public String getGuildIdA() {
        return this.squadA.getGuildId();
    }

    @Override
    public String getGuildIdB() {
        return this.squadB.getGuildId();
    }

    @Override
    public GuildSession getGuildASession() {
        return squadA;
    }

    @Override
    public GuildSession getGuildBSession() {
        return squadB;
    }

    @Override
    public AttackDetail warAttackStart(PlayerSession playerSession, String opponentId, long time) {
        PlayerSession opponentSession = ServiceFactory.instance().getSessionManager().getPlayerSession(opponentId);
        WarNotificationData warNotificationData = new WarNotificationData(this.getWarId());
        warNotificationData.setOpponentId(opponentId);
        warNotificationData.setOpponentName(opponentSession.getPlayerSettings().getName());

        SquadNotification attackStartNotification =
                createNotification(playerSession.getGuildSession().getGuildId(),
                        playerSession.getGuildSession().getGuildName(), playerSession, SquadMsgType.warPlayerAttackStart);
        attackStartNotification.setData(warNotificationData);

        AttackDetail attackDetail = ServiceFactory.instance().getPlayerDatasource().warAttackStart(this,
                playerSession.getPlayerId(), opponentId, attackStartNotification, time);

        return attackDetail;
    }

    @Override
    public void warMatched() {
        GuildSession guildSession1 = ServiceFactory.instance().getSessionManager().getGuildSession(this.getGuildIdA());
        SquadNotification warPreparedNotification =
                createNotification(guildSession1.getGuildId(), guildSession1.getGuildName(),
                        null, SquadMsgType.warPrepared);
        warPreparedNotification.setData(new WarNotificationData(warId));

        WarNotification warNotification = ServiceFactory.instance().getPlayerDatasource()
                .warPrepared(this, getWarId(), warPreparedNotification);
    }

    @Override
    public AttackDetail warAttackComplete(PlayerSession playerSession,
                                          BattleReplay battleReplay, Map<String, Integer> attackingUnitsKilled,
                                          DefendingWarParticipant defendingWarParticipant, long time)
    {
        // we override the planet as this is war which is on sullust
        battleReplay.battleLog.planetId = "planet24";
        battleReplay.replayData.combatEncounter.map.planet = battleReplay.battleLog.planetId;

        WarNotificationData warNotificationData = new WarNotificationData(this.getWarId());
        SquadNotification attackCompleteNotification =
                createNotification(playerSession.getGuildSession().getGuildId(),
                        playerSession.getGuildSession().getGuildName(), playerSession, SquadMsgType.warPlayerAttackComplete);
        attackCompleteNotification.setData(warNotificationData);

        warNotificationData.setOpponentId(battleReplay.battleLog.defender.playerId);
        warNotificationData.setOpponentName(battleReplay.battleLog.defender.name);

        SquadNotification attackReplayNotification =
                createWarReplayNotification(playerSession, battleReplay);

        playerSession.warBattleComplete(battleReplay.battleLog.battleId, battleReplay.battleLog.stars,
                attackingUnitsKilled, time);

        AttackDetail attackDetail = ServiceFactory.instance().getPlayerDatasource().warAttackComplete(this,
                playerSession, battleReplay, attackCompleteNotification, attackReplayNotification,
                defendingWarParticipant,
                time);

        return attackDetail;
    }

    private SquadNotification createWarReplayNotification(PlayerSession playerSession,
                                                          BattleReplay battleReplay)
    {
        ShareBattleNotificationData shareBattleNotificationData =
                ShareBattleHelper.createBattleNotification(playerSession, battleReplay);

        SquadNotification attackReplayNotification =
                createNotification(playerSession.getGuildSession().getGuildId(),
                        playerSession.getGuildSession().getGuildName(), playerSession, SquadMsgType.shareBattle);
        attackReplayNotification.setData(shareBattleNotificationData);
        attackReplayNotification.setMessage(playerSession.getPlayerSettings().getName() + " attacking " +
                battleReplay.battleLog.defender.name);

        return attackReplayNotification;
    }

    @Override
    public void processGuildGet(long time) {
        War currentWar = getOrLoadWar();
        if (time >= currentWar.getActionEndTime()) {
            if (currentWar.getProcessedEndTime() == 0) {
                this.guildGetLock.lock();
                try {
                    if (currentWar.getProcessedEndTime() == 0) {
                        processWarEnd(currentWar);
                        this.getGuildASession().setDirty();
                        this.getGuildBSession().setDirty();
                    }
                } finally {
                    this.guildGetLock.unlock();;
                }
            }
        }
    }

    private void processWarEnd(War currentWar) {
        War war = ServiceFactory.instance().getPlayerDatasource().processWarEnd(warId, currentWar.getSquadIdA(),
                currentWar.getSquadIdB());
        currentWar.setProcessedEndTime(war.getProcessedEndTime());
        this.setDirty();
    }

    private War getOrLoadWar() {
        War currentWar = this.war;

        if (currentWar == null || this.lastLoadedWarTime < this.dirtyTime) {
            this.warLock.lock();
            try {
                if (currentWar == null || this.lastLoadedWarTime < this.dirtyTime) {
                    this.war = ServiceFactory.instance().getPlayerDatasource().getWar(this.getWarId());
                    this.lastLoadedWarTime = System.currentTimeMillis();
                }

                currentWar = this.war;
            } finally {
                this.warLock.unlock();
            }
        }

        return currentWar;
    }

    @Override
    public void setDirty() {
        this.dirtyTime = System.currentTimeMillis();
    }
}
