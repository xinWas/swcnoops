package swcnoops.server.datasource;

import com.mongodb.*;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.*;
import org.bson.conversions.Bson;
import org.mongojack.JacksonMongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import swcnoops.server.Config;
import swcnoops.server.ServiceFactory;
import swcnoops.server.commands.guild.GuildHelper;
import swcnoops.server.commands.player.PlayerIdentitySwitch;
import swcnoops.server.datasource.buffers.FixedRingBuffer;
import swcnoops.server.datasource.buffers.RingBuffer;
import swcnoops.server.game.*;
import swcnoops.server.model.*;
import swcnoops.server.requests.ResponseHelper;
import swcnoops.server.session.*;
import swcnoops.server.session.creature.CreatureManager;
import swcnoops.server.session.training.BuildUnits;
import swcnoops.server.session.training.DeployableQueue;
import swcnoops.server.session.training.TrainingManager;
import java.util.*;
import java.util.Date;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.*;

public class PlayerDatasourceImpl implements PlayerDataSource {
    private static final Logger LOG = LoggerFactory.getLogger(PlayerDatasourceImpl.class);

    private MongoClient mongoClient;
    private JacksonMongoCollection<Player> playerCollection;
    private JacksonMongoCollection<SquadInfo> squadCollection;
    private JacksonMongoCollection<SquadNotification> squadNotificationCollection;
    private JacksonMongoCollection<DevBase> devBaseCollection;
    private JacksonMongoCollection<BattleReplay> battleReplayCollection;
    private JacksonMongoCollection<WarSignUp> warSignUpCollection;
    private JacksonMongoCollection<SquadWar> squadWarCollection;
    private JacksonMongoCollection<SquadMemberWarData> squadMemberWarDataCollection;
    private JacksonMongoCollection<PlayerWarMap> playerWarMapCollection;

    private MongoDatabase database;
    private JacksonMongoCollection<Patch> patchesCollection;

    public PlayerDatasourceImpl() {
    }

    @Override
    public void initOnStartup() {
        initMongoClient();
    }

    @Override
    public void shutdown() {
        if (this.mongoClient != null)
            this.mongoClient.close();
    }

    private void initMongoClient() {
        ConnectionString connectionString =
                new ConnectionString(ServiceFactory.instance().getConfig().mongoDBConnection);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .serverApi(ServerApi.builder()
                        .version(ServerApiVersion.V1)
                        .build())
                .build();

        String mongoDBName = ServiceFactory.instance().getConfig().mongoDBName;

        this.mongoClient = MongoClients.create(settings);
        this.database = mongoClient.getDatabase(mongoDBName);
        this.playerCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "player", Player.class, UuidRepresentation.STANDARD);

        // TODO - need to check if this is actually being used for PvP
        this.playerCollection.createIndex(compoundIndex(Indexes.descending("playerSettings.faction"),
                Indexes.descending("playerSettings.protectedUntil"),
                Indexes.descending("currentPvPDefence"),
                Indexes.descending("keepAlive"),
                Indexes.descending("playerSettings.hqLevel"),
                Indexes.descending("playerSettings.scalars.xp")));

        // this unique index is used to prevent 2 players picking the same opponent
        this.playerCollection.createIndex(Indexes.ascending("currentPvPAttack.playerId"),
                new IndexOptions().unique(true).partialFilterExpression(exists("currentPvPAttack.playerId")));

        // TODO - need to check if the index is being used
        this.playerCollection.createIndex(compoundIndex(Indexes.ascending("tournaments.uid"), Indexes.descending("tournaments.value")),
                new IndexOptions().partialFilterExpression(exists("tournaments.uid")));

        this.squadCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "squad", SquadInfo.class, UuidRepresentation.STANDARD);

        this.squadCollection.createIndex(Indexes.ascending("squadMembers.playerId"), new IndexOptions().unique(true)
                .partialFilterExpression(exists("squadMembers.playerId")));
        this.squadCollection.createIndex(Indexes.ascending("faction"));
        this.squadCollection.createIndex(Indexes.text("name"));

        this.squadNotificationCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "squadNotification", SquadNotification.class, UuidRepresentation.STANDARD);

        this.squadNotificationCollection.createIndex(compoundIndex(Indexes.ascending("guildId"),
                Indexes.ascending("playerId"), Indexes.ascending("type"), Indexes.descending("date")));

        this.devBaseCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "devBase", DevBase.class, UuidRepresentation.STANDARD);

        this.devBaseCollection.createIndex(Indexes.text("fileName"));
        this.devBaseCollection.createIndex(Indexes.ascending("checksum"));
        // TODO - need to check if this is actually being used for PvP
        this.devBaseCollection.createIndex(compoundIndex(Indexes.ascending("hq"), Indexes.ascending("xp")));

        this.battleReplayCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "battleReplay", BattleReplay.class, UuidRepresentation.STANDARD);
        this.battleReplayCollection.createIndex(compoundIndex(Indexes.ascending("attackerId"),
                Indexes.descending("battleType"),
                Indexes.descending("attackDate")));
        this.battleReplayCollection.createIndex(compoundIndex(Indexes.ascending("defenderId"),
                Indexes.descending("battleType"),
                Indexes.descending("attackDate")));

        this.warSignUpCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "warSignUp", WarSignUp.class, UuidRepresentation.STANDARD);
        this.warSignUpCollection.createIndex(Indexes.ascending("guildId"), new IndexOptions().unique(true));

        this.squadWarCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "squadWar", SquadWar.class, UuidRepresentation.STANDARD);
        this.squadWarCollection.createIndex(compoundIndex(Indexes.ascending("squadIdA"),
                Indexes.descending("processedEndTime")));
        this.squadWarCollection.createIndex(compoundIndex(Indexes.ascending("squadIdB"),
                Indexes.descending("processedEndTime")));

        this.squadMemberWarDataCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "squadMemberWarData", SquadMemberWarData.class, UuidRepresentation.STANDARD);

        this.squadMemberWarDataCollection.createIndex(compoundIndex(Indexes.ascending("id"),
                Indexes.ascending("guildId"),
                Indexes.ascending("warId")),
                new IndexOptions().unique(true));

        this.patchesCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "patches", Patch.class, UuidRepresentation.STANDARD);
        this.patchesCollection.createIndex(Indexes.ascending("patchName"), new IndexOptions().unique(true));

        this.playerWarMapCollection = JacksonMongoCollection.builder()
                .build(this.mongoClient, mongoDBName, "playerWarMap", PlayerWarMap.class, UuidRepresentation.STANDARD);
    }

    @Override
    public Player loadPlayer(String playerId) {
        Player player = this.playerCollection.find(eq("_id", playerId)).first();
        setGuildInfo(player, playerId);
        return player;
    }

    private void setGuildInfo(Player player, String playerId) {
        SquadInfo squadInfo = this.squadCollection.find(eq("squadMembers.playerId", playerId))
                .projection(include("_id", "name")).first();
        if (squadInfo != null) {
            player.getPlayerSettings().setGuildId(squadInfo._id);
            player.getPlayerSettings().setGuildName(squadInfo.name);
        } else {
            // if this is a selfDonateSquad then we dont want to clear it
            String guildId = player.getPlayerSettings().getGuildId();
            if (guildId != null && !guildId.equals(playerId)) {
                player.getPlayerSettings().setGuildId(null);
                player.getPlayerSettings().setGuildName(null);
            }
        }
    }

    @Override
    public PlayerSettings loadPlayerSettings(String playerId, boolean includeGuildId, String... fieldNames) {
        Player player = this.loadPlayer(playerId, includeGuildId, fieldNames);
        return player.getPlayerSettings();
    }

    @Override
    public Player loadPlayer(String playerId, boolean includeGuildId, String... fieldNames) {
        Player player = this.playerCollection.find(eq("_id", playerId))
                .projection(include(fieldNames)).first();

        if (includeGuildId) {
            setGuildInfo(player, playerId);
        }

        return player;
    }

    @Override
    public void savePlayerName(PlayerSession playerSession, String playerName) {
        playerSession.getPlayer().setKeepAlive(ServiceFactory.getSystemTimeSecondsFromEpoch());
        Bson simpleUpdate = set("playerSettings.name", playerName);
        Bson simpleUpdateKeepAlive = set("keepAlive", playerSession.getPlayer().getKeepAlive());
        Bson combined = combine(simpleUpdate, simpleUpdateKeepAlive);
        UpdateResult result = this.playerCollection.updateOne(Filters.eq("_id", playerSession.getPlayerId()),
                combined);

        playerSession.getPlayer().getPlayerSettings().setName(playerName);
    }

    @Override
    public void savePlayerSession(PlayerSession playerSession) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            savePlayerSettings(playerSession, session);
            session.commitTransaction();
            playerSession.doneDBSave();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to save player session " + playerSession.getPlayerId(), e);
        }
    }

    @Override
    public void savePlayerLogin(PlayerSession playerSession) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            savePlayerSettings(playerSession, new Date(), session);
            clearLastPvPAttack(session, playerSession.getPvpSession());
            session.commitTransaction();
            playerSession.doneDBSave();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to save player login " + playerSession.getPlayerId(), e);
        }
    }

    @Override
    public void savePlayerKeepAlive(PlayerSession playerSession) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            savePlayerKeepAlive(session, playerSession);
            session.commitTransaction();
            playerSession.doneDBSave();
        }
    }

    private void savePlayerKeepAlive(ClientSession clientSession, PlayerSession playerSession) {
        this.savePlayerSettingsSmart(clientSession, playerSession);
    }

    @Override
    public void recoverWithPlayerSettings(PlayerSession playerSession, PlayerModel playerModel, Map<String, String> sharedPrefs) {
        ServiceFactory.instance().getSessionManager().resetPlayerSettings(playerSession.getPlayerSettings());
        ServiceFactory.instance().getSessionManager().setFromModel(playerSession.getPlayerSettings(), playerModel);

        playerSession.getPlayer().setKeepAlive(ServiceFactory.getSystemTimeSecondsFromEpoch());
        playerSession.getPlayerSettings().getSharedPreferences().putAll(sharedPrefs);
        playerSession.getPlayer().getPlayerSecret().setMissingSecret(false);

        Bson simpleUpdate = set("playerSettings", playerSession.getPlayerSettings());
        Bson recoverUpdate = set("playerSecret.missingSecret", playerSession.getPlayer().getPlayerSecret().getMissingSecret());
        Bson keepAliveUpdate = set("keepAlive", playerSession.getPlayer().getKeepAlive());
        Bson combined = combine(recoverUpdate, simpleUpdate, keepAliveUpdate);
        UpdateResult result = this.playerCollection.updateOne(Filters.eq("_id", playerSession.getPlayerId()),
                combined);

        // reload and initialise
        Player player = this.loadPlayer(playerSession.getPlayerId());
        playerSession.initialise(player);
    }

    @Override
    public void joinSquad(GuildSession guildSession, PlayerSession playerSession, SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            playerSession.setGuildSession(guildSession);
            savePlayerSettings(playerSession, clientSession);

            if (guildSession.canEdit()) {
                Member newMember = GuildHelper.createMember(playerSession);
                newMember.joinDate = ServiceFactory.getSystemTimeSecondsFromEpoch();
                // playerId only works across documents and not in the same document
                Bson combinedQuery = combine(eq("_id", guildSession.getGuildId()),
                        Filters.ne("squadMembers.playerId", newMember.playerId));
                UpdateResult result = this.squadCollection.updateOne(clientSession, combinedQuery,
                        combine(push("squadMembers", newMember), inc("members", 1)));

                deleteJoinRequestNotifications(clientSession, squadNotification.getGuildId(), squadNotification.getPlayerId());
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            }

            clientSession.commitTransaction();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to join player to guild " + playerSession.getPlayerId(), e);
        } finally {
            guildSession.setDirty();
        }
    }

    private void startTransaction(ClientSession clientSession) {
        clientSession.startTransaction(TransactionOptions.builder() .writeConcern(WriteConcern.MAJORITY).build());
    }

    @Override
    public void joinRequest(GuildSession guildSession, PlayerSession playerSession, SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            savePlayerSettings(playerSession, clientSession);

            if (guildSession.canEdit()) {
                deleteJoinRequestNotifications(clientSession, squadNotification.getGuildId(), squadNotification.getPlayerId());
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            }
            clientSession.commitTransaction();
        } catch (MongoCommandException ex) {
            throw new RuntimeException("Failed to save player settings id=" + playerSession.getPlayerId(), ex);
        } finally {
            guildSession.setDirty();
        }
    }

    @Override
    public void joinRejected(GuildSession guildSession, PlayerSession playerSession, SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);

            if (guildSession.canEdit()) {
                deleteJoinRequestNotifications(clientSession, squadNotification.getGuildId(), squadNotification.getPlayerId());
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            }
            clientSession.commitTransaction();
        } catch (MongoCommandException ex) {
            throw new RuntimeException("Failed to save player settings id=" + playerSession.getPlayerId(), ex);
        } finally {
            guildSession.setDirty();
        }
    }

    private void deleteJoinRequestNotifications(ClientSession clientSession, String guildId, String playerId) {
        Bson combine = combine(eq("guildId", guildId), eq("playerId", playerId), eq("type", SquadMsgType.joinRequest));
        DeleteResult deleteResult = this.squadNotificationCollection.deleteMany(clientSession, combine);
    }

    @Override
    public void leaveSquad(GuildSession guildSession, PlayerSession playerSession, SquadNotification squadNotification) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            savePlayerSettings(playerSession, session);
            if (guildSession.canEdit()) {
                Bson combine = combine(pull("squadMembers", eq("playerId",playerSession.getPlayerId())),
                        inc("members", -1));
                UpdateResult result = this.squadCollection.updateOne(session,
                        combine(eq("_id", guildSession.getGuildId()), eq("squadMembers.playerId", playerSession.getPlayerId())),
                        combine);

                deleteNotifications(session, squadNotification.getGuildId(), playerSession.getPlayerId());
                setAndSaveGuildNotification(session, guildSession, squadNotification);
            }

            session.commitTransaction();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to join player to guild " + playerSession.getPlayerId(), e);
        } finally {
            guildSession.setDirty();
        }
    }

    private void deleteNotifications(ClientSession clientSession, String guildId, String playerId) {
        Bson combine = combine(eq("guildId", guildId), eq("playerId", playerId));
        DeleteResult deleteResult = this.squadNotificationCollection.deleteMany(clientSession, combine);
    }

    @Override
    public void changeSquadRole(GuildSession guildSession, PlayerSession invokerSession, PlayerSession playerSession, SquadNotification squadNotification,
                                SquadRole squadRole) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            this.savePlayerKeepAlive(clientSession, invokerSession);
            if (guildSession.canEdit()) {
                updateSquadMember(clientSession, guildSession.getGuildId(), playerSession.getPlayerId(),
                        squadRole == SquadRole.Officer);
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            }
            clientSession.commitTransaction();
            invokerSession.doneDBSave();
        } catch (MongoCommandException ex) {
            throw new RuntimeException("Failed to save squad member role id=" + playerSession.getPlayerId(), ex);
        } finally {
            guildSession.setDirty();
        }
    }

    private void updateSquadMember(ClientSession clientSession, String guildId, String playerId, boolean isOfficer) {
        UpdateResult result = this.squadCollection.updateOne(clientSession,
                combine(eq("_id", guildId), eq("squadMembers.playerId", playerId)),
                set("squadMembers.$.isOfficer", isOfficer));
    }

    private void saveDonationRecipient(PlayerSession playerSession, String planetId, int amount, ClientSession session) {
        // TODO - redo these to do straight through amendments to the settings
        mapDonatedTroopsToPlayerSession(playerSession);
        Bson donatedTroops = set("playerSettings.donatedTroops", playerSession.getPlayer().getPlayerSettings().getDonatedTroops());
        Bson donated = inc("receivedDonations." + planetId, amount);
        Player result = this.playerCollection.findOneAndUpdate(session, Filters.eq("_id", playerSession.getPlayerId()),
                combine(donatedTroops, donated));
        playerSession.getReceivedDonationsManager().setDirty();
    }

    private void savePlayerSettings(PlayerSession playerSession, ClientSession session) {
        savePlayerSettings(playerSession, null, session);
    }

    private void savePlayerSettingsSmart(ClientSession session, PlayerSession playerSession) {
        playerSession.getPlayer().setKeepAlive(ServiceFactory.getSystemTimeSecondsFromEpoch());
        List<Bson> combinedList = new ArrayList<>();
        combinedList.add(set("keepAlive", playerSession.getPlayer().getKeepAlive()));

        if (playerSession.getInventoryManager().needsSaving()) {
            combinedList.add(set("playerSettings.inventoryStorage", playerSession.getInventoryManager().getObjectForSaving()));
            playerSession.getPlayerSettings().setInventoryStorage(playerSession.getInventoryManager().getObjectForSaving());
        }

        if (playerSession.getScalarsManager().needsSaving()) {
            combinedList.add(set("playerSettings.scalars", playerSession.getScalarsManager().getObjectForSaving()));
            playerSession.getPlayerSettings().setScalars(playerSession.getScalarsManager().getObjectForSaving());
        }

        if (playerSession.getCurrentPvPAttack().needsSaving()) {
            combinedList.add(set("currentPvPAttack", playerSession.getCurrentPvPAttack().getObjectForSaving()));
        }

        if (playerSession.getTournamentManager().needsSaving()) {
            combinedList.add(set("playerSettings.tournaments", playerSession.getTournamentManager().getObjectForSaving()));
        }

        if (playerSession.getRaidLogsManager().needsSaving()) {
            combinedList.add(set("playerSettings.raidLogs", playerSession.getRaidLogsManager().getObjectForSaving()));
        }

        if (playerSession.getProtectionManager().needsSaving()) {
            combinedList.add(set("playerSettings.protectedUntil", playerSession.getProtectionManager().getObjectForSaving()));
        }

        if (playerSession.getPlayerObjectivesManager().needsSaving()) {
            combinedList.add(set("playerSettings.playerObjectives", playerSession.getPlayerObjectivesManager().getObjectForSaving()));
        }

        Bson combinedSet = combine(combinedList);
        Player result = this.playerCollection.findOneAndUpdate(session, Filters.eq("_id", playerSession.getPlayerId()),
                combinedSet);
    }

    // TODO - probably will need to change this to be smart and only update amended data
    private void savePlayerSettings(PlayerSession playerSession, Date loginDate, ClientSession session) {
        // TODO - redo these to do straight through amendments to the settings
        mapDeployablesToPlayerSettings(playerSession);
        playerSession.getPlayerSettings().setBuildContracts(mapContractsToPlayerSettings(playerSession));
        mapCreatureToPlayerSession(playerSession);
        mapDonatedTroopsToPlayerSession(playerSession);

        int oldHqLevel = playerSession.getPlayerSettings().getHqLevel();
        int oldXp = playerSession.getScalarsManager().getObjectForReading().xp;

        int hqLevel = playerSession.getHeadQuarter().getBuildingData().getLevel();
        // TODO - this might need to change based on if the building has completed constructing/building yet or not
        int xp = ServiceFactory.getXpFromBuildings(playerSession.getPlayerMapItems().getBaseMap().buildings);
        playerSession.getPlayerSettings().setHqLevel(hqLevel);
        playerSession.getScalarsManager().getObjectForWriting().xp = xp;

        // if there is a change then we need to tell the squad
        GuildSession guildSession = playerSession.getGuildSession();
        if (guildSession != null && (oldHqLevel != hqLevel || oldXp != xp)) {
            // TODO - do we need to send a squad notification otherwise how will the squad know
            // might be able to trick the client by sending a joinRequestRejected without data or something like that
            setSquadPlayerHQandXp(session, guildSession.getGuildId(), playerSession.getPlayerId(), hqLevel, xp);
            guildSession.getMembersManager().setDirty();
        }

        // map DBCache objects
        // map for inventoryStorage
        if (playerSession.getInventoryManager().needsSaving()) {
            playerSession.getPlayerSettings().setInventoryStorage(playerSession.getInventoryManager().getObjectForSaving());
        }
        if (playerSession.getScalarsManager().needsSaving()) {
            playerSession.getPlayerSettings().setScalars(playerSession.getScalarsManager().getObjectForSaving());
        }
        if (playerSession.getTournamentManager().needsSaving()) {
            playerSession.getPlayerSettings().setTournaments(playerSession.getTournamentManager().getObjectForSaving());
        }
        if (playerSession.getRaidLogsManager().needsSaving()) {
            playerSession.getPlayerSettings().setRaidLogs(playerSession.getRaidLogsManager().getObjectForSaving());
        }
        if (playerSession.getProtectionManager().needsSaving()) {
            playerSession.getPlayerSettings().setProtectedUntil(playerSession.getProtectionManager().getObjectForSaving());
        }
        if (playerSession.getPlayerObjectivesManager().needsSaving()) {
            playerSession.getPlayerSettings().setPlayerObjectives(playerSession.getPlayerObjectivesManager().getObjectForSaving());
        }

        Bson playerQuery = null;
        List<Bson> combinedList = new ArrayList<>();

        // if we are logging in then we need to check to see if we are being attacked
        PvpAttack currentPvPDefence = playerSession.getCurrentPvPDefence().getObjectForReading();
        if (loginDate != null) {
            playerSession.getPlayer().setLoginDate(loginDate);
            playerSession.getPlayer().setLoginTime(ServiceFactory.getSystemTimeSecondsFromEpoch());
            playerSession.getPlayerSettings().setDamagedBuildings(null);
            combinedList.add(set("loginDate", playerSession.getPlayer().getLoginDate()));
            combinedList.add(set("loginTime", playerSession.getPlayer().getLoginTime()));

            // see if we can remove the attack because it has expired, meaning the attacker crashed
            if (currentPvPDefence != null) {
                if (currentPvPDefence.expiration < ServiceFactory.getSystemTimeSecondsFromEpoch()) {
                    playerSession.getCurrentPvPDefence().setDirty();
                    playerQuery = combine(eq("_id", playerSession.getPlayerId()),
                            eq("currentPvPDefence.expiration", currentPvPDefence.expiration));
                    currentPvPDefence = null;

                    combinedList.add(unset("currentPvPDefence"));
                }
            }
        }

        if (playerQuery == null) {
            playerQuery = eq("_id", playerSession.getPlayerId());
        }

        if (currentPvPDefence == null) {
//            LOG.info("In Save for inventory credit amount " + playerSession.getPlayer().getPlayerSettings().getInventoryStorage().credits.amount);
            playerSession.getPlayer().setKeepAlive(ServiceFactory.getSystemTimeSecondsFromEpoch());
            combinedList.add(set("keepAlive", playerSession.getPlayer().getKeepAlive()));
            combinedList.add(set("playerSettings", playerSession.getPlayerSettings()));

            Bson combinedSet = combine(combinedList);
            UpdateResult result = this.playerCollection.updateOne(session, playerQuery,
                    combinedSet);
        }
    }

    private void mapDonatedTroopsToPlayerSession(PlayerSession playerSession) {
        // replace the players troops with new data before saving
        DonatedTroops donatedTroops = playerSession.getDonatedTroops();
        PlayerSettings playerSettings = playerSession.getPlayerSettings();
        playerSettings.setDonatedTroops(donatedTroops);
    }

    private void mapCreatureToPlayerSession(PlayerSession playerSession) {
        // replace the players settings with new data before saving
        PlayerSettings playerSettings = playerSession.getPlayerSettings();
        CreatureManager creatureManager = playerSession.getCreatureManager();
        playerSettings.setCreature(creatureManager.getCreature());
    }

    private BuildUnits mapContractsToPlayerSettings(PlayerSession playerSession) {
        BuildUnits allContracts = new BuildUnits();
        allContracts.addAll(playerSession.getTrainingManager().getDeployableTroops().getUnitsInQueue());
        allContracts.addAll(playerSession.getTrainingManager().getDeployableChampion().getUnitsInQueue());
        allContracts.addAll(playerSession.getTrainingManager().getDeployableHero().getUnitsInQueue());
        allContracts.addAll(playerSession.getTrainingManager().getDeployableSpecialAttack().getUnitsInQueue());
        allContracts.addAll(playerSession.getDroidManager().getUnitsInQueue());
        return allContracts;
    }

    private void mapDeployablesToPlayerSettings(PlayerSession playerSession) {
        Deployables deployables = playerSession.getPlayerSettings().getDeployableTroops();
        TrainingManager trainingManager = playerSession.getTrainingManager();
        mapToPlayerSetting(trainingManager.getDeployableTroops(), deployables.troop);
        mapToPlayerSetting(trainingManager.getDeployableChampion(), deployables.champion);
        mapToPlayerSetting(trainingManager.getDeployableHero(), deployables.hero);
        mapToPlayerSetting(trainingManager.getDeployableSpecialAttack(), deployables.specialAttack);
    }

    private void mapToPlayerSetting(DeployableQueue deployableQueue, Map<String, Integer> storage) {
        storage.clear();
        storage.putAll(deployableQueue.getDeployableUnits());
    }

    @Override
    public void saveTroopDonation(GuildSession guildSession, PlayerSession playerSession, PlayerSession recipientPlayerSession,
                                  String planetId, int amount, SquadNotification squadNotification)
    {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            savePlayerSettings(playerSession, clientSession);
            saveDonationRecipient(recipientPlayerSession, planetId, amount, clientSession);
            setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            clientSession.commitTransaction();
        } catch (MongoCommandException ex) {
            throw new RuntimeException("Failed to save player settings id=" + playerSession.getPlayerId() +
                    " and id=" + recipientPlayerSession.getPlayerId(), ex);
        }
    }

    @Override
    public void newPlayer(String playerId, String secret, PlayerModel playerModel, Map<String, String> sharedPrefs, String name) {
        newPlayer(playerId, secret, false, playerModel, sharedPrefs, name);
    }

    @Override
    public void newPlayerWithMissingSecret(String playerId, String secret, PlayerModel playerModel,
                                           Map<String, String> sharedPrefs, String name)
    {
        newPlayer(playerId, secret, true, playerModel, sharedPrefs, name);
    }

    private void newPlayer(String playerId, String secret, boolean missingSecret, PlayerModel playerModel,
                           Map<String, String> sharedPrefs, String name)
    {
        Player player = new Player(playerId);
        player.setPlayerSecret(new PlayerSecret(secret, null, missingSecret));
        player.setPlayerSettings(new PlayerSettings());
        ServiceFactory.instance().getSessionManager().setFromModel(player.getPlayerSettings(), playerModel);
        if (sharedPrefs != null)
            player.getPlayerSettings().getSharedPreferences().putAll(sharedPrefs);
        player.getPlayerSettings().setName(name);

        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            // creating a secondary account so we update the primary one
            if (playerId.endsWith("_1")) {
                String primaryAccount = PlayerIdentitySwitch.getPrimaryAccount(playerId);
                this.playerCollection.updateOne(clientSession, eq("_id", primaryAccount),
                        set("playerSecret.secondaryAccount", playerId));
            }
            this.playerCollection.insertOne(clientSession, player);
            clientSession.commitTransaction();
        }
    }

    @Override
    public void newGuild(PlayerSession playerSession, Squad squad) {
        SquadInfo squadInfo = new SquadInfo();
        squadInfo._id = squad._id;
        squadInfo.name = squad.name;
        squadInfo.openEnrollment = squad.openEnrollment;
        squadInfo.icon = squad.icon;
        squadInfo.faction = squad.faction;
        squadInfo.description = squad.description;
        squadInfo.minScore = squad.minScore;
        squadInfo.created = squad.created;
        Member owner = GuildHelper.createMember(playerSession);
        owner.isOwner = true;
        owner.joinDate = ServiceFactory.getSystemTimeSecondsFromEpoch();
        squadInfo.getSquadMembers().add(owner);
        squadInfo.members = squadInfo.getSquadMembers().size();
        squadInfo.activeMemberCount = squadInfo.getSquadMembers().size();

        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            playerSession.getPlayerSettings().setGuildId(squadInfo._id);
            playerSession.getPlayerSettings().setGuildName(squadInfo.name);
            savePlayerSettings(playerSession, clientSession);
            this.squadCollection.insertOne(clientSession, squadInfo);
            clientSession.commitTransaction();
            playerSession.doneDBSave();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to create new guild player id " + playerSession.getPlayerId(), e);
        }
    }

    @Override
    public GuildSettings loadGuildSettings(String guildId) {
        GuildSettingsImpl guildSettings = null;

        SquadInfo squadInfo = this.squadCollection.find(eq("_id", guildId)).first();

        if (squadInfo != null) {
            guildSettings = new GuildSettingsImpl(squadInfo._id);
            guildSettings.setSquad(squadInfo);
            guildSettings.setMembers(squadInfo.getSquadMembers());
        }

        return guildSettings;
    }

    @Override
    public Squad loadSquad(String guildId) {
        SquadInfo squadInfo = this.squadCollection.findOne(eq("_id", guildId),
                include("name", "faction", "description", "icon", "openEnrollment",
                        "minScore", "warSignUpTime", "warId", "members", "score", "rank",
                        "activeMemberCount", "level", "created"));

        return squadInfo;
    }

    @Override
    public List<Member> loadSquadMembers(String guildId) {
        SquadInfo squadInfo = this.squadCollection.find(eq("_id", guildId)).projection(include("squadMembers")).first();
        if (squadInfo != null)
            return squadInfo.getSquadMembers();

        return new ArrayList<>();
    }

    @Override
    public List<WarHistory> loadWarHistory(String squadId) {
        // TODO - change to use aggregate to limit to the latest 20
        Bson squadInWar = or(eq("squadIdA", squadId), eq("squadIdB", squadId));
        FindIterable<SquadWar> squadWarIterable = this.squadWarCollection.find(and(squadInWar, gt("processedEndTime", 0)))
                .projection(include("warId", "squadIdA", "squadIdB", "processedEndTime", "squadAScore", "squadBScore",
                        "squadAWarSignUp.guildName", "squadAWarSignUp.icon", "squadBWarSignUp.guildName", "squadBWarSignUp.icon"));

        List<WarHistory> warHistories = new ArrayList<>();

        try (MongoCursor<SquadWar> cursor = squadWarIterable.cursor()) {
            while (cursor.hasNext()) {
                SquadWar squadWar = cursor.next();

                WarHistory warHistory = new WarHistory();
                warHistory.warId = squadWar.getWarId();
                warHistory.opponentGuildId = squadWar.getSquadIdB();
                warHistory.opponentName = squadWar.getSquadBWarSignUp().guildName;
                warHistory.opponentIcon = squadWar.getSquadBWarSignUp().icon;
                warHistory.opponentScore = squadWar.getSquadBScore();
                warHistory.endDate = squadWar.getProcessedEndTime();

                if (!warHistory.opponentGuildId.equals(squadId)) {
                    warHistory.score = squadWar.getSquadAScore();
                } else {
                    warHistory.opponentGuildId = squadWar.getSquadIdA();
                    warHistory.opponentName = squadWar.getSquadAWarSignUp().guildName;
                    warHistory.opponentIcon = squadWar.getSquadAWarSignUp().icon;
                    warHistory.opponentScore = squadWar.getSquadAScore();
                    warHistory.score = squadWar.getSquadBScore();
                }

                warHistories.add(warHistory);
            }
        }

        return warHistories;
    }


    @Override
    public void editGuild(GuildSession guildSession, String guildId, String description, String icon, Integer minScoreAtEnrollment,
                          boolean openEnrollment) {
        try {
            Bson setDescription = set("description", description);
            Bson setIcon = set("icon", icon);
            Bson setMinScoreAtEnrollment = set("minScore", minScoreAtEnrollment);
            Bson setOpenEnrollment = set("openEnrollment", openEnrollment);
            Bson combined = combine(setDescription, setIcon, setMinScoreAtEnrollment, setOpenEnrollment);
            // TODO - no notification is sent so squad does not know, there does not seem to be a notification
            // for this, maybe can use some other one to trick it
            UpdateResult result = this.squadCollection.updateOne(Filters.eq("_id", guildId),
                    combined);
        } finally {
            guildSession.getSquadManager().setDirty();
        }
    }

    @Override
    public List<Squad> getGuildList(FactionType faction) {
        FindIterable<SquadInfo> squadInfos = this.squadCollection.find(eq("faction", faction))
                .projection(include("faction", "name", "description", "icon", "openEnrollment", "minScore", "members", "activeMemberCount"));

        List<Squad> squads = new ArrayList<>();
        if (squadInfos != null) {
            try (MongoCursor<SquadInfo> cursor = squadInfos.cursor()) {
                while (cursor.hasNext()) {
                    SquadInfo squadInfo = cursor.next();
                    squads.add(squadInfo);
                }
            }
        }

        return squads;
    }

    @Override
    public PlayerSecret getPlayerSecret(String primaryId) {
        Player player = this.playerCollection.find(eq("_id", primaryId)).projection(include("playerSecret")).first();

        PlayerSecret playerSecret = null;
        if (player != null) {
            playerSecret = player.getPlayerSecret();
        }

        return playerSecret;
    }

    @Override
    public void saveNotification(GuildSession guildSession, SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            clientSession.commitTransaction();
        } catch (MongoCommandException e) {
            throw new RuntimeException("Failed to set and save notification for guild " + guildSession.getGuildId(), e);
        }
    }

    @Override
    public List<Squad> searchGuildByName(String searchTerm) {
        FindIterable<SquadInfo> squadInfos = this.squadCollection.find(regex("name", searchTerm, "i"))
                .projection(include("faction", "name", "description", "icon", "openEnrollment", "minScore", "members", "activeMemberCount"));

        List<Squad> squads = new ArrayList<>();
        if (squadInfos != null) {
            try (MongoCursor<SquadInfo> cursor = squadInfos.cursor()) {
                while (cursor.hasNext()) {
                    SquadInfo squadInfo = cursor.next();
                    squads.add(squadInfo);
                }
            }
        }

        return squads;
    }

    @Override
    public boolean saveWarSignUp(FactionType faction, GuildSession guildSession, List<String> participantIds,
                              boolean isSameFactionWarAllowed, SquadNotification squadNotification, long time) {
        boolean saved = false;

        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            if (checkAndResetSquadWarParty(clientSession, guildSession)) {
                saveWarSignUp(clientSession, faction, guildSession, participantIds, isSameFactionWarAllowed, time);
                setSquadWarParty(clientSession, guildSession, participantIds, time);
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
                clientSession.commitTransaction();
                saved = true;
            }
        } catch (MongoWriteException ex) {
            // if duplicate key then most likely already signed up
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                saved = false;
            } else {
                throw ex;
            }
        } finally {
            guildSession.setDirty();
        }

        return saved;
    }

    private void saveWarSignUp(ClientSession clientSession, FactionType faction, GuildSession guildSession, List<String> participantIds,
                               boolean isSameFactionWarAllowed, long time)
    {
        WarSignUp warSignUp = new WarSignUp();
        Squad squad = guildSession.getSquadManager().getObjectForReading();
        warSignUp.faction = faction;
        warSignUp.guildId = squad._id;
        warSignUp.guildName = squad.name;
        warSignUp.icon = squad.icon;
        warSignUp.participantIds = participantIds;
        warSignUp.isSameFactionWarAllowed = isSameFactionWarAllowed;
        warSignUp.time = time;
        warSignUp.signUpdate = new Date();
        // TODO - how to handle if the squad has already signed up, need to be able to detect that scenario
        this.warSignUpCollection.insertOne(clientSession, warSignUp);
    }

    private void setSquadPlayerHQandXp(ClientSession clientSession, String guildId, String playerId,
                                  int hqLevel, int xp)
    {
        Squad result = this.squadCollection.findOneAndUpdate(clientSession,
                and(eq("_id", guildId), Filters.eq("squadMembers.playerId", playerId)),
                combine(set("squadMembers.$.hqLevel", hqLevel), set("squadMembers.$.xp", xp)));
    }

    private void setSquadWarParty(ClientSession clientSession, GuildSession guildSession, List<String> participantIds,
                                  long time)
    {
        try {
            UpdateResult result = this.squadCollection.updateOne(clientSession,
                    eq("_id", guildSession.getGuildId()),
                    combine(set("squadMembers.$[m].warParty", 1), set("warSignUpTime", time)),
                    new UpdateOptions().arrayFilters(Arrays.asList(in("m.playerId", participantIds))));
        } finally {
            guildSession.getSquadManager().setDirty();
        }
    }

    private boolean checkAndResetSquadWarParty(ClientSession clientSession, GuildSession guildSession) {
        boolean doneReset = false;
        try {
            SquadInfo squadInfo = this.squadCollection.findOneAndUpdate(clientSession,
                    combine(eq("_id", guildSession.getGuildId()), exists("warSignUpTime", false)),
                    combine(set("squadMembers.$[].warParty", 0), unset("warId")));

            if (squadInfo != null) {
                doneReset = true;
            } else {
                LOG.warn("Failed to reset squad for match making " + guildSession.getGuildId());
            }
        } finally {
            guildSession.getSquadManager().setDirty();
        }

        return doneReset;
    }

    private void cancelWarSignUp(ClientSession clientSession, GuildSession guildSession) {
        try {
            this.squadCollection.updateOne(clientSession, eq("_id", guildSession.getGuildId()),
                    combine(set("squadMembers.$[].warParty", 0), unset("warSignUpTime"),
                            unset("warId")));
        } finally {
            guildSession.getSquadManager().setDirty();
        }
    }

    private void clearWarParty(ClientSession clientSession, String warId, String guildId) {
        this.squadCollection.findOneAndUpdate(clientSession, combine(eq("_id", guildId), eq("warId", warId)),
                combine(set("squadMembers.$[].warParty", 0), unset("warSignUpTime")));
    }

    @Override
    public boolean cancelWarSignUp(GuildSession guildSession, SquadNotification squadNotification) {
        boolean cancelled = false;
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            if (deleteWarSignUp(session, guildSession.getGuildId())) {
                cancelWarSignUp(session, guildSession);
                setAndSaveGuildNotification(session, guildSession, squadNotification);
                session.commitTransaction();
                cancelled = true;
            } else {
                LOG.warn("Failed to cancel war sign up for squad " + guildSession.getGuildId());
            }
        } finally {
            guildSession.setDirty();
        }

        return cancelled;
    }

    private boolean deleteWarSignUp(ClientSession clientSession, String guildId) {
        WarSignUp deleteResult = this.warSignUpCollection.findOneAndDelete(clientSession, eq("guildId", guildId));
        return deleteResult != null;
    }

    @Override
    public String matchMake(String guildId) {
        String warId;
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            warId = matchMake(session, guildId);
            session.commitTransaction();
        }

        return warId;
    }

    private String matchMake(ClientSession session, String guildId) {
        SquadWar squadWar = null;
        WarSignUp mySquad = this.warSignUpCollection.findOne(eq("guildId", guildId));
        if (mySquad != null) {
            List<Bson> aggregates = Arrays.asList(Aggregates.match(ne("guildId", guildId)),
                    Aggregates.sample(1));
            AggregateIterable<WarSignUp> otherSquadIterable = this.warSignUpCollection.aggregate(session, aggregates);
            if (otherSquadIterable.cursor().hasNext()) {
                WarSignUp opponent = otherSquadIterable.cursor().next();
                if (opponent != null) {
                    DeleteResult deleteResult = this.warSignUpCollection.deleteMany(session,
                            or(eq("guildId", guildId), eq("guildId", opponent.guildId)));

                    squadWar = this.createWar(session, mySquad, opponent);
                    this.setSquadWarId(session, mySquad.guildId, squadWar.getWarId());
                    this.setSquadWarId(session, opponent.guildId, squadWar.getWarId());
                    this.createWarParticipants(session, squadWar);
                }
            }
        }

        String warId = null;
        if (squadWar != null)
            warId = squadWar.getWarId();

        return warId;
    }

    private void setSquadWarId(ClientSession clientSession, String guildId, String warId) {
        SquadInfo result = this.squadCollection.findOneAndUpdate(clientSession,
                eq("_id", guildId),
                set("warId", warId));
    }

    private void createWarParticipants(ClientSession session, SquadWar squadWar) {
        List<SquadMemberWarData> squadMembersA = createWarParticipants(squadWar.getWarId(), squadWar.getSquadAWarSignUp());
        List<SquadMemberWarData> squadMembersB = createWarParticipants(squadWar.getWarId(), squadWar.getSquadBWarSignUp());
        this.squadMemberWarDataCollection.insertMany(session, squadMembersA);
        this.squadMemberWarDataCollection.insertMany(session, squadMembersB);
    }

    private List<SquadMemberWarData> createWarParticipants(String warId, WarSignUp warSignUp) {
        List<SquadMemberWarData> squadMembers = new ArrayList<>();
        for (String playerId : warSignUp.participantIds) {
            if (!playerId.contains("BOT")) {
                Player player = this.playerCollection.find(eq("_id", playerId))
                        .projection(include("playerSettings.baseMap",
                                "playerSettings.name", "playerSettings.hqLevel")).first();

                PlayerWarMap playerWarMap = this.playerWarMapCollection.findOne(eq("_id", playerId));

                SquadMemberWarData squadMemberWarData = new SquadMemberWarData();
                squadMemberWarData.warId = warId;
                squadMemberWarData.id = playerId;
                squadMemberWarData.guildId = warSignUp.guildId;
                squadMemberWarData.turns = 3;
                squadMemberWarData.victoryPoints = 3;

                PlayerMap playerMap = playerWarMap != null ? playerWarMap : null;
                if (playerMap == null)
                    playerMap = player.getPlayerSettings().getBaseMap();

                squadMemberWarData.warMap = playerMap;
                MapHelper.enableTraps(squadMemberWarData.warMap);
                squadMemberWarData.name = player.getPlayerSettings().getName();
                squadMemberWarData.level = player.getPlayerSettings().getHqLevel();
                squadMembers.add(squadMemberWarData);
            }
        }

        return squadMembers;
    }

    private SquadWar createWar(ClientSession session, WarSignUp mySquad, WarSignUp opponent) {
        long warMatchedTime = ServiceFactory.getSystemTimeSecondsFromEpoch();

        SquadWar squadWar = new SquadWar();
        squadWar.setWarId(ServiceFactory.createRandomUUID());
        squadWar.setSquadIdA(mySquad.guildId);
        squadWar.setSquadIdB(opponent.guildId);
        Config config = ServiceFactory.instance().getConfig();
        squadWar.warMatchedTime = warMatchedTime;
        squadWar.warMatchedDate = new Date();
        squadWar.setPrepGraceStartTime(warMatchedTime += config.warPlayerPreparationDuration);
        squadWar.setPrepEndTime(warMatchedTime += config.warServerPreparationDuration);
        squadWar.setActionGraceStartTime(warMatchedTime += config.warPlayDuration);
        squadWar.setActionEndTime(warMatchedTime += config.warResultDuration);
        squadWar.setCooldownEndTime(warMatchedTime + config.warCoolDownDuration);
        squadWar.setSquadAWarSignUp(mySquad);
        squadWar.setSquadBWarSignUp(opponent);

        this.squadWarCollection.insertOne(session, squadWar);
        return squadWar;
    }

    @Override
    public War getWar(String warId) {
        War war = this.squadWarCollection.findOne(eq("_id", warId));
        return war;
    }

    @Override
    public SquadMemberWarData loadPlayerWarData(String warId, String playerId) {
        SquadMemberWarData squadMemberWarData =
                this.squadMemberWarDataCollection.findOne(combine(eq("warId", warId),
                        eq("id", playerId)));

        return squadMemberWarData;
    }

    @Override
    public List<SquadMemberWarData> getWarParticipants(String guildId, String warId) {
        FindIterable<SquadMemberWarData> squadMemberWarDataFindIterable =
                this.squadMemberWarDataCollection.find(combine(eq("guildId", guildId), eq("warId", warId)));

        List<SquadMemberWarData> squadMemberWarDatums = new ArrayList<>();
        try (MongoCursor<SquadMemberWarData> cursor = squadMemberWarDataFindIterable.cursor()) {
            while (cursor.hasNext()) {
                squadMemberWarDatums.add(cursor.next());
            }
        }

        return squadMemberWarDatums;
    }

    @Override
    public void saveWarMap(PlayerSession playerSession, SquadMemberWarData squadMemberWarData) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            savePlayerSettings(playerSession, clientSession);
            SquadMemberWarData updatedData = this.squadMemberWarDataCollection.findOneAndUpdate(clientSession,
                    combine(eq("warId", squadMemberWarData.warId),
                            eq("id", squadMemberWarData.id)),
                    set("warMap", squadMemberWarData.warMap));
            clientSession.commitTransaction();
        }
    }

    @Override
    public void saveWarTroopDonation(GuildSession guildSession, PlayerSession playerSession, SquadMemberWarData squadMemberWarData,
                                     SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            savePlayerSettings(playerSession, clientSession);
            saveWarTroopDonation(squadMemberWarData, clientSession);
            setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            clientSession.commitTransaction();
        }
    }

    private void saveWarTroopDonation(SquadMemberWarData squadMemberWarData, ClientSession clientSession) {
        SquadMemberWarData updatedData = this.squadMemberWarDataCollection.findOneAndUpdate(clientSession,
                combine(eq("warId", squadMemberWarData.warId),
                        eq("id", squadMemberWarData.id)),
                set("donatedTroops", squadMemberWarData.donatedTroops));
    }

    @Override
    public AttackDetail warAttackStart(WarSession warSession, String playerId, String opponentId,
                                       SquadNotification attackStartNotification, long time) {
        AttackDetail attackDetail;
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            attackDetail = checkAndCreateWarBattleId(session, warSession.getWarId(), playerId, opponentId, time);
            if (attackDetail.getBattleId() != null) {
                WarNotificationData warNotificationData = (WarNotificationData) attackStartNotification.getData();
                warNotificationData.setAttackExpirationDate(attackDetail.getExpirationDate());
                setAndSaveWarNotification(session, attackDetail, warSession, attackStartNotification);
            }

            if (attackDetail.getReturnCode() == ResponseHelper.RECEIPT_STATUS_COMPLETE)
                session.commitTransaction();
            else
                session.abortTransaction();
        }

        return attackDetail;
    }

    @Override
    public AttackDetail warAttackComplete(WarSession warSession, PlayerSession playerSession,
                                          BattleReplay battleReplay,
                                          SquadNotification attackCompleteNotification,
                                          SquadNotification attackReplayNotification,
                                          DefendingWarParticipant defendingWarParticipant, long time) {
        AttackDetail attackDetail;
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());

            // calculate how many stars earned in the attack
            int victoryPointsRemaining = defendingWarParticipant.getVictoryPoints();
            int victoryPointsEarned = (3 - victoryPointsRemaining) - battleReplay.battleLog.stars;
            if (victoryPointsEarned < 0)
                victoryPointsEarned = Math.abs(victoryPointsEarned);
            else
                victoryPointsEarned = 0;

            attackDetail = saveAndUpdateWarBattle(session, warSession.getWarId(), battleReplay, victoryPointsEarned);

            if (attackDetail.getReturnCode() == ResponseHelper.RECEIPT_STATUS_COMPLETE) {
                savePlayerSettings(playerSession, session);
                WarNotificationData warNotificationData = (WarNotificationData) attackCompleteNotification.getData();
                warNotificationData.setStars(battleReplay.battleLog.stars);
                warNotificationData.setVictoryPoints(victoryPointsEarned);
                setAndSaveWarNotification(session, attackDetail, warSession, attackCompleteNotification);
                setAndSaveWarNotification(session, attackDetail, warSession, attackReplayNotification);
                saveBattleReplay(session, battleReplay);
                session.commitTransaction();
            } else {
                session.abortTransaction();
            }
        }

        return attackDetail;
    }

    @Override
    public DefendingWarParticipant getDefendingWarParticipantByBattleId(String battleId) {
        SquadMemberWarData squadMemberWarData =
                this.squadMemberWarDataCollection.findOne(eq("defenseBattleId", battleId),
                        include("id", "victoryPoints"));

        if (squadMemberWarData == null)
            throw new RuntimeException("unable to find battleId in WarParticipants " + battleId);

        DefendingWarParticipant defendingWarParticipant =
                new DefendingWarParticipant(squadMemberWarData.id, squadMemberWarData.victoryPoints);

        return defendingWarParticipant;
    }

    private AttackDetail saveAndUpdateWarBattle(ClientSession clientSession, String warId,
                                                BattleReplay battleReplay,
                                                int victoryPointsEarned)
    {
        SquadMemberWarData defenderMemberWarData =
                this.squadMemberWarDataCollection.findOneAndUpdate(clientSession, combine(eq("warId", warId),
                                eq("defenseBattleId", battleReplay.getBattleId())),
                        combine(unset("defenseBattleId"),
                                unset("defenseExpirationDate"),
                                inc("victoryPoints", -1 * victoryPointsEarned)));

        boolean updated = defenderMemberWarData != null;

        if (updated) {
            SquadMemberWarData attackerMemberWarData =
                    this.squadMemberWarDataCollection.findOneAndUpdate(clientSession, combine(eq("warId", warId),
                                    eq("attackBattleId", battleReplay.getBattleId())),
                            combine(unset("attackBattleId"),
                                    unset("attackExpirationDate"),
                                    inc("score", victoryPointsEarned)));

            updated = attackerMemberWarData != null;
        }

        int response = ResponseHelper.RECEIPT_STATUS_COMPLETE;

        // TODO - not sure what to send back yet
        if (!updated)
            response = ResponseHelper.STATUS_CODE_NOT_MODIFIED;

        AttackDetail attackDetail = new AttackDetail(response);
        return attackDetail;
    }

    private void setAndSaveWarNotification(ClientSession clientSession, WarNotification warNotification, WarSession warSession,
                                           SquadNotification squadNotification) {
        synchronized (warSession) {
            GuildSession guildSessionA = warSession.getGuildASession();
            squadNotification.setDate(0);
            setAndSaveGuildNotification(clientSession, guildSessionA, squadNotification);
            warNotification.setGuildANotificationDate(squadNotification.getDate());
            GuildSession guildSessionB = warSession.getGuildBSession();
            squadNotification.setDate(0);
            setAndSaveGuildNotification(clientSession, guildSessionB, squadNotification);
            warNotification.setGuildBNotificationDate(squadNotification.getDate());
        }
    }

    private void setAndSaveGuildNotification(ClientSession clientSession, GuildSession guildSession, SquadNotification squadNotification) {
        synchronized (guildSession) {
            try {
                if (squadNotification.getDate() == 0)
                    squadNotification.setDate(ServiceFactory.getSystemTimeSecondsFromEpoch());
                // have to reset the ID otherwise a shared notification saving to mongo will fail
                squadNotification.setId(ServiceFactory.createRandomUUID());
                squadNotification.setGuildId(guildSession.getGuildId());
                squadNotification.setGuildName(guildSession.getGuildName());
                saveNotification(clientSession, squadNotification);
            } finally {
                guildSession.setNotificationDirty(squadNotification.getDate());
            }
        }
    }

    private void saveNotification(ClientSession clientSession, SquadNotification squadNotification) {
        this.squadNotificationCollection.insertOne(clientSession, squadNotification);
    }

    private AttackDetail checkAndCreateWarBattleId(ClientSession session, String warId, String playerId, String opponentId, long time) {
        AttackDetail attackDetail = null;

        try {
            String defenseBattleId = ServiceFactory.createRandomUUID();
            long defenseExpirationDate = ServiceFactory.getSystemTimeSecondsFromEpoch() +
                    ServiceFactory.instance().getConfig().attackDuration;

            // TODO - change this to findOneAndUpdate to only update if no one has claimed it yet
            Bson defenseMatch = and(eq("warId", warId), eq("id", opponentId),
                    gt("victoryPoints", 0),
                    or(eq("defenseBattleId", null), lt("defenseExpirationDate", time - 10)));
            SquadMemberWarData result = this.squadMemberWarDataCollection.findOneAndUpdate(session, defenseMatch,
                    combine(set("defenseBattleId", defenseBattleId), set("defenseExpirationDate", defenseExpirationDate)));

            if (result != null) {
                attackDetail = new AttackDetail(defenseBattleId, defenseExpirationDate);
            }

            if (attackDetail != null) {
                Bson attackerMatch = and(eq("warId", warId), eq("id", playerId),
                        gt("turns", 0));
                SquadMemberWarData updatePlayersTurns = this.squadMemberWarDataCollection.findOneAndUpdate(session, attackerMatch,
                        combine(set("attackBattleId", defenseBattleId),
                                set("attackExpirationDate", defenseExpirationDate),
                                inc("turns", -1)));

                // if not changed then ran out of turns
                if (updatePlayersTurns == null) {
                    attackDetail = new AttackDetail(ResponseHelper.STATUS_CODE_GUILD_WAR_NOT_ENOUGH_TURNS);
                }
            } else {
                // no battle id generated means it is getting attacked by someone already
                SquadMemberWarData opponentData = this.squadMemberWarDataCollection.findOne(combine(eq("warId", warId),
                        eq("id", opponentId)), include("id", "victoryPoints", "defenseExpirationDate"));

                // base has been cleared
                if (opponentData.victoryPoints == 0) {
                    attackDetail = new AttackDetail(ResponseHelper.STATUS_CODE_GUILD_WAR_NOT_ENOUGH_VICTORY_POINTS);
                } else if (time > opponentData.defenseExpirationDate) {
                    // TODO - give a grace time on when to decide to unlock this base as possible client crash
                    System.out.println("WarBase is still being attacked but expiry time has finished, maybe client crashed?");
                    attackDetail = new AttackDetail(ResponseHelper.STATUS_CODE_GUILD_WAR_BASE_UNDER_ATTACK);
                } else {
                    attackDetail = new AttackDetail(ResponseHelper.STATUS_CODE_GUILD_WAR_BASE_UNDER_ATTACK);
                }
            }

        } catch (Exception ex) {
            throw new RuntimeException("Failed to checkAndCreateWarBattleId for player id=" + playerId, ex);
        }

        return attackDetail;
    }

    @Override
    public void deleteWarForSquads(War war) {
        if (war != null) {
            try (ClientSession session = this.mongoClient.startSession()) {
                session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
                this.squadCollection.updateMany(eq("warId", war.getWarId()),
                        combine(unset("warId"), unset("warSignUpTime"), set("squadMembers.$[].warParty", 0)));
                session.commitTransaction();
            } finally {
                GuildSession guildSession1 = ServiceFactory.instance().getSessionManager().getGuildSession(war.getSquadIdA());
                GuildSession guildSession2 = ServiceFactory.instance().getSessionManager().getGuildSession(war.getSquadIdB());
                guildSession1.setDirty();
                guildSession2.setDirty();
            }
        }
    }

    @Override
    public void saveWar(War war) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            this.squadWarCollection.updateOne(session, eq("_id", war.getWarId()),
                    combine(set("prepGraceStartTime", war.getPrepGraceStartTime()),
                            set("prepEndTime", war.getPrepEndTime()),
                            set("actionGraceStartTime", war.getActionGraceStartTime()),
                            set("actionEndTime", war.getActionEndTime()),
                            set("cooldownEndTime", war.getCooldownEndTime()),
                            set("processedEndTime", war.getProcessedEndTime())));
            session.commitTransaction();
        }
    }

    @Override
    public Collection<SquadNotification> getSquadNotificationsSince(String guildId, String guildName, long since) {
        FindIterable<SquadNotification> squadNotifications =
                this.squadNotificationCollection.find(combine(eq("guildId", guildId), gt("date", since)));

        List<SquadNotification> notifications = new ArrayList<>();
        try (MongoCursor<SquadNotification> cursor = squadNotifications.cursor()) {
            while (cursor.hasNext()) {
                SquadNotification squadNotification = cursor.next();
                notifications.add(squadNotification);
            }
        }

        return notifications;
    }

    @Override
    public WarNotification warPrepared(WarSessionImpl warSession, String warId, SquadNotification warPreparedNotification) {
        WarNotification warNotification = new WarNotification();
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            setAndSaveWarNotification(session, warNotification, warSession, warPreparedNotification);
            session.commitTransaction();
        } finally {
            warSession.getGuildASession().getSquadManager().setDirty();
            warSession.getGuildBSession().getSquadManager().setDirty();
        }

        return warNotification;
    }

    @Override
    public void pvpReleaseTarget(PvpManager pvpManager) {
        PvpMatch pvpMatch = pvpManager.getCurrentPvPMatch();
        if (pvpMatch != null) {
            try (ClientSession clientSession = this.mongoClient.startSession()) {
                startTransaction(clientSession);
                clearLastPvPAttack(clientSession, pvpManager);
                clientSession.commitTransaction();
            }
        }
    }

    @Override
    public PvpMatch getPvPMatches(PvpManager pvpManager, Set<String> playersSeen) {
        PvpMatch pvpMatch = getPvPMatchWithRetry(pvpManager, playersSeen, 2);
        return pvpMatch;
    }

    @Override
    public void battleShare(GuildSessionImpl guildSession, PlayerSession playerSession, SquadNotification squadNotification) {
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            savePlayerSettings(playerSession, clientSession);
            if (guildSession.canEdit()) {
                setAndSaveGuildNotification(clientSession, guildSession, squadNotification);
            }
            clientSession.commitTransaction();
        } catch (MongoCommandException ex) {
            throw new RuntimeException("Failed to save battleShare for player id=" + playerSession.getPlayerId(), ex);
        }
    }

    @Override
    public PvpMatch getPvPRevengeMatch(PvpManager pvpManager, String opponentId, long time) {
        PlayerSession playerSession = pvpManager.getPlayerSession();
        long timeNow = ServiceFactory.getSystemTimeSecondsFromEpoch();

        // TODO - maybe do something here for protected player, or when player is on different planet, logged in etc...
        // should return the correct error message rather than not found
        Bson isOpponent = eq("_id", opponentId);
        Bson notBeingAttacked = or(eq("currentPvPDefence", null), lt("currentPvPDefence.expiration", timeNow));
        Bson playerNotPlaying = or(lt("keepAlive", timeNow - 130), eq("keepAlive", null));
        Bson match = Aggregates.match(and(isOpponent, notBeingAttacked, playerNotPlaying));

        // revenge is free
        int creditsCharge = 0;
        PvpMatch pvpMatch = createPvpMatch(match, creditsCharge, playerSession, pvpManager, timeNow);

        if (pvpMatch != null) {
            pvpMatch.setRevenge(true);
            pvpMatch.setDevBase(false);
        }

        return pvpMatch;
    }

    private PvpMatch createPvpMatch(Bson match, int creditCharge, PlayerSession playerSession, PvpManager pvpManager, long time) {
        List<Bson> aggregates = Arrays.asList(match,
                Aggregates.project(include("playerSettings.baseMap", "playerSettings.name",
                        "playerSettings.faction", "playerSettings.hqLevel",
                        "playerSettings.guildId", "playerSettings.guildName",
                        "playerSettings.inventoryStorage", "playerSettings.scalars", "playerSettings.damagedBuildings",
                        "currentPvPDefence", "playerSettings.donatedTroops", "playerSettings.deployableTroops.champion",
                        "playerSettings.creature", "playerSettings.troops", "playerSettings.tournaments",
                        "playerSettings.protectedUntil")),
                Aggregates.sample(1));

        PvpMatch pvpMatch = null;
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);

            // clear last PvP attack
            clearLastPvPAttack(clientSession, pvpManager);
            AggregateIterable<Player> inactivePlayersIterable = this.playerCollection.aggregate(clientSession, aggregates);

            try (MongoCursor<Player> inactivePlayerCursor = inactivePlayersIterable.cursor()) {
                if (inactivePlayerCursor.hasNext()) {
                    Player opponentPlayer = inactivePlayerCursor.next();
                    pvpMatch = mapPvPMatch(playerSession.getPlayerId(), creditCharge, opponentPlayer, time);
                    // they were attacked but has not been cleaned up, we will clean up the attacker otherwise
                    // unique index will fail
                    if (opponentPlayer.getCurrentPvPDefence() != null) {
                        PvpAttack pvpAttackDefence = opponentPlayer.getCurrentPvPDefence();
                        clearPvPAttacker(clientSession, pvpAttackDefence);
                    }
                }
            }

            // TODO - need to reset attackers protection if its a revenge attack (need to see what client does)
            // pvp match will only have isPlayerProtected if this is a revenge attack
            if (pvpMatch != null && !pvpMatch.isPlayerProtected()) {
                PvpAttack pvpAttack = deductCreditForPvPAttack(pvpManager, pvpMatch);
                playerSession.getProtectionManager().setObjectForSaving(Long.valueOf(0));
                this.savePlayerSettingsSmart(clientSession, playerSession);

                PvpAttack pvpDefence = new PvpAttack();
                pvpDefence.playerId = pvpManager.getPlayerSession().getPlayerId();
                pvpDefence.battleId = pvpAttack.battleId;
                pvpDefence.expiration = pvpAttack.expiration;
                this.playerCollection.findOneAndUpdate(clientSession, eq("_id", pvpAttack.playerId),
                        set("currentPvPDefence", pvpDefence));
            }

            clientSession.commitTransaction();
            playerSession.doneDBSave();
        }

        return pvpMatch;
    }

    private PvpMatch mapPvPMatch(String attackerId, int creditsCharged, Player opponentPlayer, long time) {
        PvpMatch pvpMatch = new PvpMatch();
        pvpMatch.setBattleId(ServiceFactory.createRandomUUID());
        pvpMatch.setBattleDate(ServiceFactory.getSystemTimeSecondsFromEpoch());
        pvpMatch.setPlayerId(attackerId);
        pvpMatch.setParticipantId(opponentPlayer.getPlayerId());
        pvpMatch.setDefenderXp(opponentPlayer.getPlayerSettings().getScalars().xp);
        pvpMatch.setFactionType(opponentPlayer.getPlayerSettings().getFaction());
        pvpMatch.setLevel(opponentPlayer.getPlayerSettings().getHqLevel());
        pvpMatch.setDefendersName(opponentPlayer.getPlayerSettings().getName());
        pvpMatch.setDefendersGuildId(opponentPlayer.getPlayerSettings().getGuildId());
        pvpMatch.setDefendersGuildName(opponentPlayer.getPlayerSettings().getGuildName());
        pvpMatch.setDefendersBaseMap(opponentPlayer.getPlayerSettings().getBaseMap());
        pvpMatch.setDefendersDonatedTroops(opponentPlayer.getPlayerSettings().getDonatedTroops());
        pvpMatch.setDefenderDamagedBuildings(opponentPlayer.getPlayerSettings().getDamagedBuildings());
        pvpMatch.setDefendersScalars(opponentPlayer.getPlayerSettings().getScalars());
        pvpMatch.setDefendersInventoryStorage(opponentPlayer.getPlayerSettings().getInventoryStorage());
        pvpMatch.setDefendersDeployableTroopsChampion(opponentPlayer.getPlayerSettings().getDeployableTroops().champion);
        pvpMatch.setDefendersCreature(opponentPlayer.getPlayerSettings().getCreature());
        pvpMatch.setDefendersTroops(opponentPlayer.getPlayerSettings().getTroops());
        pvpMatch.setDefendersTournaments(opponentPlayer.getPlayerSettings().getTournaments());
        pvpMatch.creditsCharged = creditsCharged;
        pvpMatch.setProtectedUntil(opponentPlayer.getPlayerSettings().getProtectedUntil());
        pvpMatch.setPlayerProtected(time);
        return pvpMatch;
    }

    private PvpMatch getPvPMatchWithRetry(PvpManager pvpManager, Set<String> playersSeen, int attempts) {
        PvpMatch pvpMatch = null;
        do {
            try {
                pvpMatch = getPvPMatch(pvpManager, playersSeen);
                attempts--;
            } catch (MongoCommandException e) {
                // for now we retry again but might need to add some smart things as no point retrying for all exceptions
                LOG.warn("An error trying to get a PvP match", e);
            }
        } while (pvpMatch == null && attempts > 0);

        return pvpMatch;
    }

    private PvpMatch getPvPMatch(PvpManager pvpManager, Set<String> playersSeen) throws MongoCommandException {
        PlayerSession playerSession = pvpManager.getPlayerSession();
        long timeNow = ServiceFactory.getSystemTimeSecondsFromEpoch();
        int playerHq = playerSession.getPlayerSettings().getHqLevel();
        int playerXp = playerSession.getScalarsManager().getObjectForReading().xp;

        Bson hqQuery = and(gte("playerSettings.hqLevel", playerHq - 1), lte("playerSettings.hqLevel", playerHq + 1));
        Bson xpQuery = and(gte("playerSettings.scalars.xp", playerXp * 0.9), lte("playerSettings.scalars.xp", playerXp * 1.10));
        Bson notMe = ne("_id", pvpManager.getPlayerSession().getPlayerId());
        Bson notAlreadySeen = nin("_id", playersSeen);
        Bson notBeingAttacked = or(eq("currentPvPDefence", null), lt("currentPvPDefence.expiration", timeNow));
        Bson notProtected = or(eq("playerSettings.protectedUntil", null), lt("playerSettings.protectedUntil", timeNow));
        Bson otherFaction = ne("playerSettings.faction", pvpManager.getPlayerSession().getPlayer().getPlayerSettings().getFaction());
        Bson playerNotPlaying = or(lt("keepAlive", timeNow - 130), eq("keepAlive", null));
        Bson match = Aggregates.match(and(notMe, otherFaction, notBeingAttacked, notProtected, playerNotPlaying, notAlreadySeen,
                or(hqQuery, xpQuery)));

        int creditsCharged = ServiceFactory.instance().getGameDataManager().getPvpMatchCost(playerHq);
        PvpMatch pvpMatch = createPvpMatch(match, creditsCharged, playerSession, pvpManager, timeNow);

        if (pvpMatch != null) {
            pvpMatch.setDevBase(false);
        }

        return pvpMatch;
    }

    // TODO - might have to change how the credit gets deducted for PvP as the cost is not when
    // GetNextTarget is done, instead it is done on PvpBattleStart/BattleComplete/PvpReleasedTarget
    // to do this properly may need to record how many GetNextTargets were called consecutively, which also
    // means handling the deduction on login to deal with the player crashing
    private PvpAttack deductCreditForPvPAttack(PvpManager pvpManager, PvpMatch pvpMatch) {
        GameConstants constants = ServiceFactory.instance().getGameDataManager().getGameConstants();

        PvpAttack pvpAttack = new PvpAttack();
        pvpAttack.playerId = pvpMatch.getParticipantId();
        pvpAttack.battleId = pvpMatch.getBattleId();
        // 30s to decide and a buffer
        pvpAttack.expiration = ServiceFactory.getSystemTimeSecondsFromEpoch() + constants.pvp_match_countdown + 8;
        pvpManager.getPlayerSession().getCurrentPvPAttack().setObjectForSaving(pvpAttack);
        CurrencyDelta currencyDelta = new CurrencyDelta(pvpMatch.creditsCharged, pvpMatch.creditsCharged, CurrencyType.credits, true);
        pvpManager.getPlayerSession().processInventoryStorage(currencyDelta);
        return pvpAttack;
    }

    private void clearPvPAttacker(ClientSession clientSession, PvpAttack pvpAttack) {
        Player player = this.playerCollection.findOneAndUpdate(clientSession,
                combine(eq("_id", pvpAttack.playerId), eq("currentPvPAttack.battleId", pvpAttack.battleId)),
                unset("currentPvPAttack"));
    }

    private void clearLastPvPAttack(ClientSession clientSession, PvpManager pvpManager) {
        Player lastPlayerValues = this.playerCollection.findOneAndUpdate(clientSession, eq("_id", pvpManager.getPlayerSession().getPlayerId()),
                unset("currentPvPAttack"), new FindOneAndUpdateOptions().projection(include("currentPvPAttack"))
                        .returnDocument(ReturnDocument.BEFORE));

        // we clear the opponent we were attacking
        boolean isDevBase = false;
        if (pvpManager.getCurrentPvPMatch() != null)
            isDevBase = pvpManager.getCurrentPvPMatch().isDevBase();

        if (lastPlayerValues.getCurrentPvPAttack() != null && !isDevBase) {
            PvpAttack pvpAttack = lastPlayerValues.getCurrentPvPAttack();
            Player lastOpponentPlayer = this.playerCollection.findOneAndUpdate(clientSession,
                    combine(eq("_id", pvpAttack.playerId), eq("currentPvPDefence.battleId", pvpAttack.battleId)),
                    unset("currentPvPDefence"), new FindOneAndUpdateOptions().projection(include("currentPvPDefence"))
                            .returnDocument(ReturnDocument.BEFORE));
        }
    }

    @Override
    public PvpMatch getDevBaseMatches(PvpManager pvpManager, Set<String> devBasesSeen) {
//        SELECT id, buildings, buildings, hqlevel, xp
//        FROM DevBases
//        WHERE (hqlevel >= ? -1 AND hqlevel <= ? +1)
//        or (xp >= ( ? * 0.9) AND xp <= (? * 1.10))
//        ORDER BY xp desc;

        PlayerSession playerSession = pvpManager.getPlayerSession();
        int playerHq = pvpManager.getPlayerSession().getHeadQuarter().getBuildingData().getLevel();
        int playerXp = pvpManager.getPlayerSession().getScalarsManager().getObjectForReading().xp;
        Bson hqQuery = and(gte("hq", playerHq - 1), lte("hq", playerHq + 1));
        Bson xpQuery = and(gte("xp", playerXp * 0.9), lte("xp", playerXp * 1.10));
        Bson notAlreadySeen = nin("_id", devBasesSeen);

        List<Bson> aggregates = Arrays.asList(Aggregates.match(and(notAlreadySeen, or(hqQuery, xpQuery))),
                Aggregates.project(include("xp", "hq")),
                Aggregates.sample(1));

        PvpMatch pvpMatch = null;
        try (ClientSession clientSession = this.mongoClient.startSession()) {
            startTransaction(clientSession);
            // clear last PvP attack
            clearLastPvPAttack(clientSession, pvpManager);

            AggregateIterable<DevBase> devBasesIterable = this.devBaseCollection.aggregate(aggregates);

            try (MongoCursor<DevBase> devBaseCursor = devBasesIterable.cursor()) {
                if (devBaseCursor.hasNext()) {
                    DevBase devBase = devBaseCursor.next();
                    pvpMatch = new PvpMatch();
                    pvpMatch.setPlayerId(pvpManager.getPlayerSession().getPlayerId());
                    pvpMatch.setParticipantId(devBase._id);
                    pvpMatch.setDefenderXp(devBase.xp);
                    pvpMatch.setBattleId(ServiceFactory.createRandomUUID());
                    pvpMatch.setFactionType(pvpManager.getPlayerSession().getFaction().equals(FactionType.empire) ? FactionType.rebel : FactionType.empire);
                    pvpMatch.setDevBase(true);
                    pvpMatch.setLevel(devBase.hq);
                    pvpMatch.creditsCharged = ServiceFactory.instance().getGameDataManager().getPvpMatchCost(playerHq);
                }
            }

            if (pvpMatch != null) {
                deductCreditForPvPAttack(pvpManager, pvpMatch);
                playerSession.getProtectionManager().setObjectForSaving(Long.valueOf(0));
                this.savePlayerSettingsSmart(clientSession, playerSession);
            }

            clientSession.commitTransaction();
            playerSession.doneDBSave();
        }

        return pvpMatch;
    }

    @Override
    public Buildings getDevBaseMap(String id, FactionType faction) {
        Buildings buildings = this.devBaseCollection.findOne(eq("_id", id)).buildings;
        for (Building building : buildings) {
            building.uid = building.uid.replace(FactionType.neutral.name(), faction.name());
        }
        return buildings;
    }

    @Override
    public void savePvPBattleComplete(PlayerSession playerSession, PvpMatch pvpMatch, BattleReplay battleReplay) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            // TODO - would like to change to smart save when it supports all the playerSetting objects
            savePlayerSettings(playerSession, session);
            saveDefendersDamage(session, pvpMatch);
            saveBattleReplay(session, battleReplay);
            clearLastPvPAttack(session, playerSession.getPvpSession());
            session.commitTransaction();
        }
    }

    private void saveDefendersDamage(ClientSession session, PvpMatch pvpMatch) {
        if (!pvpMatch.isDevBase()) {
            String defenderId = pvpMatch.getParticipantId();
            String battleId = pvpMatch.getBattleId();

            // TODO - there is a race condition here with donated troops as its possible the defender has a donation
            // while they are being attacked, this update will overwrite the donation.
            // to properly fix may need optimistic locking with retries, or a more complex
            // update that can remove the individual donation counts using inc function.
            // Will need to have a think how to do this as will require redoing donations
            List<Bson> sets = new ArrayList<>();
            sets.add(set("playerSettings.scalars", pvpMatch.getDefendersScalars()));
            sets.add(set("playerSettings.inventoryStorage", pvpMatch.getDefendersInventoryStorage()));
            sets.add(set("playerSettings.damagedBuildings", pvpMatch.getDefenderDamagedBuildings()));
            sets.add(set("playerSettings.deployableTroops.champion", pvpMatch.getDefendersDeployableTroopsChampion()));
            sets.add(set("playerSettings.baseMap", pvpMatch.getDefendersBaseMap()));
            sets.add(set("playerSettings.creature", pvpMatch.getDefendersCreature()));
            sets.add(set("playerSettings.donatedTroops", pvpMatch.getDefendersDonatedTroops()));
            sets.add(set("playerSettings.protectedUntil", pvpMatch.getDefendersProtectedUntil()));

            if (pvpMatch.getTournamentData() != null) {
                sets.add(set("playerSettings.tournaments", pvpMatch.getDefendersTournaments()));
            }

            Player player = this.playerCollection.findOneAndUpdate(session,
                    combine(eq("_id", defenderId),
                            eq("currentPvPDefence.battleId", battleId)),
                    combine(sets));
        }
    }

    private void saveBattleReplay(ClientSession session, BattleReplay battleReplay) {
        battleReplay.setDate(new Date());
        this.battleReplayCollection.insertOne(session, battleReplay);
    }

    @Override
    public List<BattleLog> getPlayerBattleLogs(String playerId) {
        List<BattleLog> battleLogs = new ArrayList<>();

        Bson pvpOnPlayer = or(eq("attackerId", playerId), eq("defenderId", playerId));
        FindIterable<BattleReplay> battleReplaysIterable =
                this.battleReplayCollection.find(and(pvpOnPlayer, eq("battleType", BattleType.Pvp)))
                        .sort(descending("attackDate"))
                        .projection(include("battleLog", "attackerId", "defenderId", "attackDate"))
                .limit(30);

        try (MongoCursor<BattleReplay> cursor = battleReplaysIterable.cursor()) {
            while (cursor.hasNext()) {
                BattleReplay battleReplay = cursor.next();
                battleLogs.add(battleReplay.battleLog);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to get DB connection when retrieving playerbattlelogs", ex);
        }

        return battleLogs;
    }

    @Override
    public BattleReplay getBattleReplay(String battleId) {
        BattleReplay battleReplay = this.battleReplayCollection.findOne(eq("_id", battleId));
        return battleReplay;
    }

    public War processWarEnd(String warId, String squadIdA, String squadIdB) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());

            SquadTotalScore squadAScore = null;
            SquadTotalScore squadBScore = null;

            List<Bson> pipeline = Arrays.asList(Aggregates.match(eq("warId", warId)),
                    Aggregates.group("$guildId", Accumulators.sum("totalScore", "$score")));

            AggregateIterable<SquadTotalScore> squadMemberWarData =
                    this.squadMemberWarDataCollection.aggregate(session, pipeline, SquadTotalScore.class);

            try (MongoCursor<SquadTotalScore> cursor = squadMemberWarData.cursor()) {
                while (cursor.hasNext()) {
                    SquadTotalScore squadTotalScore = cursor.next();
                    if (squadIdA.equals(squadTotalScore.guildId))
                        squadAScore = squadTotalScore;
                    else if (squadIdB.equals(squadTotalScore.guildId))
                        squadBScore = squadTotalScore;
                }
            }

            if (squadAScore == null || squadBScore == null)
                throw new RuntimeException("Failed to sum up squad war scores");

            long time = ServiceFactory.getSystemTimeSecondsFromEpoch();
            SquadWar squadWar = this.squadWarCollection.findOneAndUpdate(session,
                    combine(eq("_id", warId), eq("processedEndTime", 0)),
                    combine(set("processedEndTime", time),
                            set("squadAScore", squadAScore.totalScore),
                            set("squadBScore", squadBScore.totalScore)));

            if (squadWar != null) {
                clearWarParty(session, warId, squadIdA);
                clearWarParty(session, warId, squadIdB);
                copyPlayerWarMap(session, squadWar);
                session.commitTransaction();
            }
        }

        return this.getWar(warId);
    }

    private void copyPlayerWarMap(ClientSession session, SquadWar squadWar) {
        Set<String> playerIds = new HashSet<>();
        playerIds.addAll(squadWar.getSquadAWarSignUp().participantIds);
        playerIds.addAll(squadWar.getSquadBWarSignUp().participantIds);
        FindIterable<SquadMemberWarData> findIterable = this.squadMemberWarDataCollection
                .find(combine(eq("warId", squadWar.getWarId()), in("id", playerIds)))
                .projection(include("id", "warMap"));

        List<PlayerWarMap> playerWarMaps = new ArrayList<>();

        try (MongoCursor<SquadMemberWarData> cursor = findIterable.cursor()) {
            cursor.forEachRemaining(s -> {
                PlayerWarMap playerWarMap = new PlayerWarMap();
                playerWarMap.playerId = s.id;
                playerWarMap.buildings = s.warMap.buildings;
                playerWarMap.planet = s.warMap.planet;
                playerWarMap.next = s.warMap.next;
                playerWarMap.timestamp = new Date();
                playerWarMaps.add(playerWarMap);
            });
        }

        if (playerWarMaps != null && playerWarMaps.size() > 0) {
            List<UpdateOneModel<PlayerWarMap>> upserts = new ArrayList<>();
            playerWarMaps.forEach(m -> {
                BsonDocumentWrapper document = new BsonDocumentWrapper<>(m,
                        this.playerWarMapCollection.getCodecRegistry().get(PlayerWarMap.class));
                String a = document.toString();
                Document.parse(a);

                UpdateOneModel upsert = new UpdateOneModel<PlayerWarMap>(eq("_id", m.playerId),
                        new Document("$set", document), new UpdateOptions().upsert(true));
                upserts.add(upsert);
            });
            BulkWriteResult bulkWriteResult = this.playerWarMapCollection.bulkWrite(session, upserts);
        }
    }

    @Override
    public void resetWarPartyForParticipants(String warId) {
        try (ClientSession session = this.mongoClient.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            SquadWar squadWar = this.squadWarCollection.findOne(eq("_id", warId));
            GuildSession squadASession = ServiceFactory.instance().getSessionManager().getGuildSession(squadWar.getSquadIdA());
            GuildSession squadBSession = ServiceFactory.instance().getSessionManager().getGuildSession(squadWar.getSquadIdB());
            setSquadWarParty(session, squadASession, squadWar.getSquadAWarSignUp().participantIds, squadWar.getSquadAWarSignUp().time);
            setSquadWarParty(session, squadBSession, squadWar.getSquadBWarSignUp().participantIds, squadWar.getSquadBWarSignUp().time);
            session.commitTransaction();
        }
    }

    @Override
    public void saveDevBase(DevBase devBase) {
        DevBase existingDevBase = this.devBaseCollection.findOne(eq("checksum", devBase.checksum));
        if (existingDevBase == null) {
            devBase._id = ServiceFactory.createRandomUUID();
            this.devBaseCollection.save(devBase);
        } else {
            LOG.debug("dev base with checksum already exists for file " + devBase.fileName);
        }
    }

    @Override
    public void savePvPBattleStart(PlayerSession playerSession) {
        GameConstants constants = ServiceFactory.instance().getGameDataManager().getGameConstants();

        PvpMatch pvpMatch = playerSession.getPvpSession().getCurrentPvPMatch();
        if (pvpMatch != null) {
            // have to extend the expiration time as player is attacking
            PvpAttack pvpAttack = new PvpAttack();
            pvpAttack.playerId = pvpMatch.getParticipantId();
            pvpAttack.battleId = pvpMatch.getBattleId();
            pvpAttack.expiration = ServiceFactory.getSystemTimeSecondsFromEpoch() + constants.pvp_match_duration + 8;
            playerSession.getCurrentPvPAttack().setObjectForSaving(pvpAttack);

            try (ClientSession session = this.mongoClient.startSession()) {
                session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
                this.savePlayerSettingsSmart(session, playerSession);
                if (!pvpMatch.isDevBase()) {
                    this.playerCollection.updateOne(session, combine(eq("_id", pvpAttack.playerId),
                                    eq("currentPvPDefence.battleId", pvpAttack.battleId)),
                            set("currentPvPDefence.expiration", pvpAttack.expiration));
                }
                session.commitTransaction();
                playerSession.doneDBSave();
            }
        }
    }

    @Override
    public TournamentLeaderBoard getTournamentLeaderBoard(String uid, String playerId) {
        List<Bson> aggregates = Arrays.asList(Aggregates.match(eq("playerSettings.tournaments.uid", uid)),
                Aggregates.unwind("$playerSettings.tournaments"),
                Aggregates.match(eq("playerSettings.tournaments.uid", uid)),
                Aggregates.project(Projections.fields(Projections.computed("uid", "$playerSettings.tournaments.uid"),
                        Projections.computed("value", "$playerSettings.tournaments.value"),
                        Projections.computed("attacksWon", "$playerSettings.tournaments.attacksWon"),
                        Projections.computed("defensesWon", "$playerSettings.tournaments.defensesWon"),
                        Projections.computed("name", "$playerSettings.name"),
                        Projections.computed("guildId", "$playerSettings.guildId"),
                        Projections.computed("faction", "$playerSettings.faction"),
                        Projections.computed("hqLevel", "$playerSettings.hqLevel"),
                        Projections.computed("planet", "$playerSettings.baseMap.planet"))),
                Aggregates.sort(descending("value", "_id")));

        TournamentLeaderBoard tournamentLeaderBoard;

        AggregateIterable<TournamentStat> playerData = this.playerCollection.aggregate(aggregates, TournamentStat.class);
        try (MongoCursor<TournamentStat> cursor = playerData.cursor()) {
            RingBuffer top50 = new FixedRingBuffer(TournamentStat.class, 50);
            RingBuffer surroundingMe = new FixedRingBuffer(TournamentStat.class, 50);
            boolean foundPlayer = false;
            int statsAfterPlayer = 20 / 2;

            TournamentStat lastTournamentStat = null;
            while (cursor.hasNext()) {
                TournamentStat tournamentStat = cursor.next();
                if (lastTournamentStat == null) {
                    tournamentStat.rank = 1;
                } else {
                    if (tournamentStat.value == lastTournamentStat.value) {
                        tournamentStat.rank = lastTournamentStat.rank;
                    } else {
                        tournamentStat.rank = lastTournamentStat.rank + 1;
                    }
                }

                lastTournamentStat = tournamentStat;

                // have we got the top 50 yet
                if (top50.getNumberOfObjects() < top50.getCapacity()) {
                    top50.add(tournamentStat);
                }

                // find rankings surrounding player
                if (playerId != null) {
                    if (statsAfterPlayer > 0)
                        surroundingMe.add(tournamentStat);

                    if (foundPlayer)
                        statsAfterPlayer--;

                    if (tournamentStat.playerId.equals(playerId)) {
                        foundPlayer = true;
                    }
                }
            }

            tournamentLeaderBoard = new TournamentLeaderBoard(top50, surroundingMe, lastTournamentStat);
            populateWithSquadDetails(tournamentLeaderBoard);
        }

        return tournamentLeaderBoard;
    }

    @Override
    public TournamentStat getTournamentPlayerRank(String uid, String playerId) {
        List<Bson> aggregates = Arrays.asList(Aggregates.match(eq("playerSettings.tournaments.uid", uid)),
                Aggregates.unwind("$playerSettings.tournaments"),
                Aggregates.match(eq("playerSettings.tournaments.uid", uid)),
                Aggregates.project(Projections.fields(Projections.computed("uid", "$playerSettings.tournaments.uid"),
                        Projections.computed("value", "$playerSettings.tournaments.value"),
                        Projections.computed("attacksWon", "$playerSettings.tournaments.attacksWon"),
                        Projections.computed("defensesWon", "$playerSettings.tournaments.defensesWon"))),
                Aggregates.sort(descending("value", "_id")));

        AggregateIterable<TournamentStat> playerData = this.playerCollection.aggregate(aggregates, TournamentStat.class);
        TournamentStat foundPlayer = null;
        try (MongoCursor<TournamentStat> cursor = playerData.cursor()) {
            TournamentStat lastTournamentStat = null;
            while (cursor.hasNext()) {
                TournamentStat tournamentStat = cursor.next();
                if (lastTournamentStat == null) {
                    tournamentStat.rank = 1;
                } else {
                    if (tournamentStat.value == lastTournamentStat.value) {
                        tournamentStat.rank = lastTournamentStat.rank;
                    } else {
                        tournamentStat.rank = lastTournamentStat.rank + 1;
                    }
                }

                lastTournamentStat = tournamentStat;

                if (foundPlayer == null && tournamentStat.playerId.equals(playerId)) {
                    foundPlayer = tournamentStat;
                }
            }

            if (foundPlayer != null) {
                ConflictManager conflictManager = ServiceFactory.instance().getGameDataManager().getConflictManager();
                conflictManager.calculatePercentile(foundPlayer, lastTournamentStat);
            }
        }

        return foundPlayer;
    }

    private void populateWithSquadDetails(TournamentLeaderBoard tournamentLeaderBoard) {
        Set<String> guildIds = new HashSet<>();
        Iterator<TournamentStat> iterator = tournamentLeaderBoard.getTop50().iterator();
        while (iterator.hasNext()) {
            TournamentStat stat = iterator.next();
            if (stat.guildId != null)
                guildIds.add(stat.guildId);
        }

        if (tournamentLeaderBoard.getSurroundingMe() != null) {
            iterator = tournamentLeaderBoard.getSurroundingMe().iterator();
            while (iterator.hasNext()) {
                TournamentStat stat = iterator.next();
                if (stat.guildId != null)
                    guildIds.add(stat.guildId);
            }
        }

        if (guildIds.size() > 0) {
            Map<String, SquadInfo> squadInfoMap = new HashMap<>();

            FindIterable<SquadInfo> squadInfoFindIterable = this.squadCollection.find(in("_id", guildIds))
                    .projection(include("name", "icon"));
            try (MongoCursor<SquadInfo> cursor = squadInfoFindIterable.cursor()) {
                while (cursor.hasNext()) {
                    SquadInfo squadInfo = cursor.next();
                    squadInfoMap.put(squadInfo._id, squadInfo);
                }
            }

            populateWithSquadDetails(squadInfoMap, tournamentLeaderBoard.getTop50());
            populateWithSquadDetails(squadInfoMap, tournamentLeaderBoard.getSurroundingMe());
        }
    }

    private void populateWithSquadDetails(Map<String, SquadInfo> squadInfoMap, RingBuffer<TournamentStat> ringBuffer) {
        if (ringBuffer != null) {
            Iterator<TournamentStat> iterator = ringBuffer.iterator();
            while (iterator.hasNext()) {
                TournamentStat tournamentStat = iterator.next();
                if (tournamentStat.guildId != null) {
                    SquadInfo squadInfo = squadInfoMap.get(tournamentStat.guildId);
                    tournamentStat.guildName = squadInfo.name;
                    tournamentStat.icon = squadInfo.icon;
                }
            }
        }
    }
}
