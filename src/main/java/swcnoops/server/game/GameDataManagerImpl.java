package swcnoops.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import swcnoops.server.Config;
import swcnoops.server.ServiceFactory;
import swcnoops.server.datasource.DevBase;
import swcnoops.server.game.Joe.JoeFile;
import swcnoops.server.model.*;
import swcnoops.server.session.inventory.TroopRecord;
import swcnoops.server.session.inventory.Troops;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class GameDataManagerImpl implements GameDataManager {
    private Map<String, TroopData> troops = new HashMap<>();
    private Map<String, TroopData> lowestLevelTroopByUnitId = new HashMap<>();
    private Map<String, List<TroopData>> troopLevelsByUnitId = new HashMap<>();
    private Map<String, List<BuildingData>> buildingLevelsByBuildingId = new HashMap<>();
    private Map<FactionType, List<TroopData>> creaturesByFaction = new HashMap<>();
    private Map<FactionType, List<TroopData>> lowestLevelTroopByFaction = new HashMap<>();
    private Map<String, BuildingData> buildings = new HashMap<>();
    private Map<String, TrapData> traps = new HashMap<>();
    private Map<FactionType, CampaignSet> factionCampaigns = new HashMap<>();
    private Map<String, CampaignSet> campaignSets = new HashMap<>();
    private Map<String, CampaignMissionData> missionDataMap = new HashMap<>();
    private Map<String, CampaignMissionSet> campaignMissionSets = new HashMap<>();
    private Map<FactionType, Map<Integer, List<TroopData>>> troopSizeMapByFaction = new HashMap<>();
    private GameConstants gameConstants;
    private static final Logger LOG = LoggerFactory.getLogger(GameDataManagerImpl.class);
    private FactionBuildingEquivalentMap factionBuildingEquivalentMap;

    private Map<FactionType, List<Integer>> troopSizesAvailableByFaction = new HashMap<>();

    private Map<Integer, Float> pvpMedalScaling;
    private int[] pvpCosts;
    private int[] tournamentAttackerMedals;
    private int[] tournamentDefenderMedals;
    private List<Patch> availablePatches;
    private PatchData patchData = new PatchData();
    private ConflictManagerImpl conflictManager = new ConflictManagerImpl();

    private RaidManager raidManager = new RaidManagerImpl();
    private ObjectiveManager objectiveManager = new ObjectiveManagerImpl();

    @Override
    public void initOnStartup() {
        try {
            initialisePatchesAndManifest();
            loadPatches();
            loadTroops();
            loadBaseJsonAndGameConstants();
            this.traps = loadTraps();
            loadCampaigns();
            buildCustomTroopMaps();
            setupDevBases();
            this.factionBuildingEquivalentMap = create(this.buildingLevelsByBuildingId);
            this.initialiseGameConstants();
            this.initConflictManager();
            this.initRaidManager();
            this.initObjectiveManager();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load game data from patches", ex);
        }
    }

    private void initObjectiveManager() {
        Map<String, ObjSeriesData> objSeriesDataMap = this.patchData.getMap(ObjSeriesData.class);

        if (objSeriesDataMap != null) {
            for (ObjSeriesData data : objSeriesDataMap.values()) {
                data.parseJoeDates();
            }

            this.objectiveManager.setup(objSeriesDataMap.values(), this.patchData.getMap(ObjTableData.class));
        }
    }

    @Override
    public PatchData getPatchData() {
        return patchData;
    }

    private void initRaidManager() {
        Map<String, RaidData> raidDataMap = this.patchData.getMap(RaidData.class);

        if (raidDataMap != null) {
            for (RaidData data : raidDataMap.values()) {
                data.initOrderValue();
            }

            this.raidManager.setup(raidDataMap.values(), this.patchData.getMap(RaidMissionPoolData.class));
        }
    }

    @Override
    public RaidManager getRaidManager() {
        return raidManager;
    }

    @Override
    public ObjectiveManager getObjectiveManager() {
        return this.objectiveManager;
    }

    private void initConflictManager() {
        Map<String, TournamentTierData> tierDataMap = this.patchData.getMap(TournamentTierData.class);
        TournamentTierData topTier = tierDataMap.values().stream().filter(t -> t.order == 1).findFirst().get();
        this.conflictManager.setTopTier(topTier);

        Map<String, TournamentData> map = this.patchData.getMap(TournamentData.class);
        if (map != null) {
            List<TournamentData> validConflicts = new ArrayList<>();
            long now = ServiceFactory.getSystemTimeSecondsFromEpoch();
            for (TournamentData data : map.values()) {
                data.parseJoeDates();
                // conflict is still valid
                if (data.getStartTime() != 0 && data.getEndTime() != 0) {
                    if (data.getEndTime() >= now) {
                        validConflicts.add(data);
                    }
                }
            }

            this.conflictManager.setup(validConflicts);
        }
    }

    private void loadPatches() throws Exception {
        List<Patch> patches = new ArrayList<>();

        if (ServiceFactory.instance().getConfig().coreJsonPatches != null) {
            String[] corePatches = ServiceFactory.instance().getConfig().coreJsonPatches.split(";");
            for (String corePatch : corePatches) {
                Patch patch = new Patch();
                patch.patchName = corePatch;
                patches.add(patch);
            }
        }

        List<Patch> customPatches = ServiceFactory.instance().getGameDataManager().getPatchesAvailable();
        if (customPatches != null)
            patches.addAll(customPatches);

        Config config = ServiceFactory.instance().getConfig();
        String newManifestFile = config.getNewManifestTemplatePath() + Config.padManifestVersion(config.getManifestVersionToUse()) + ".json";
        Manifest manifest = ServiceFactory.instance().getJsonParser().fromJsonFile(newManifestFile, Manifest.class);
        for (Patch patch : patches) {
            ManifestPath manifestPath = manifest.paths.get("patches/" + patch.patchName);
            if (manifestPath == null) {
                throw new RuntimeException("Expected manifest " + newManifestFile + " to contain path for " + patch.patchName);
            }

            String jsonPath = config.getAssetBundlePath() + "/" + manifestPath.v + "/patches/" + patch.patchName;
            JoeFile joeFile = ServiceFactory.instance().getJsonParser().fromJsonFile(jsonPath, JoeFile.class);
            mergePatchData(joeFile);
        }
    }

    private void mergePatchData(JoeFile joeFile) throws Exception {
        if (joeFile != null) {
            this.patchData.merge(joeFile.getContent().getObjects());
        }
    }

    private void initialisePatchesAndManifest() throws Exception {
        // see if we have any patches we want to include
        List<Patch> availablePatches = loadPatchesFromFile();
        List<Patch> patchesWithJsonFile = validatePatchPath(availablePatches);

        Manifest baseManifest = ServiceFactory.instance().getJsonParser()
                .fromJsonFile(ServiceFactory.instance().getConfig().getBaseManifestPath(), Manifest.class);

        List<Patch> patchesMissing = validateWithManifest(patchesWithJsonFile, baseManifest);

        if (patchesMissing.size() > 0) {
            int patchedManifestFileVersion = createNewManifest(patchesWithJsonFile, patchesMissing, baseManifest);
            ServiceFactory.instance().getConfig().setManifestVersionToUse(patchedManifestFileVersion);
        }

        ServiceFactory.instance().getGameDataManager().setPatchesAvailable(patchesWithJsonFile);
    }

    private List<Patch> loadPatchesFromFile() throws Exception {
        List<Patch> patches = new ArrayList<>();
        String unprocessedPatchesRoot = ServiceFactory.instance().getConfig().getPatchesPath();

        Map<String, Patch> versionMap = new HashMap<>();
        File versionFile = new File(unprocessedPatchesRoot + "/version.json");
        if (versionFile.exists()) {
            PatchVersions patchVersions = ServiceFactory.instance().getJsonParser()
                    .fromJsonFile(unprocessedPatchesRoot + "/version.json", PatchVersions.class);

            if (patchVersions != null && patchVersions.patches != null) {
                for (Patch patch : patchVersions.patches) {
                    versionMap.put(patch.patchName, patch);
                }
            }
        }

        List<File> patchFiles = GameDataManagerImpl.listFiles(unprocessedPatchesRoot);
        for (File patchFile : patchFiles) {
            String patchName = patchFile.getName();
            if (patchName.toLowerCase().endsWith(".json") && !patchName.equalsIgnoreCase("version.json")) {
                Patch patch = new Patch();
                patch.patchName = patchFile.getName();
                patch.crcChecksum = Long.toHexString(GameDataManagerImpl.getCRC32Checksum(new FileInputStream(patchFile))).toUpperCase();

                Patch patchVersion = versionMap.get(patchName);
                if (patchVersion != null) {
                    patch.version = patchVersion.version;
                }

                patches.add(patch);
            }
        }

        return patches;
    }

    private int createNewManifest(List<Patch> availablePatches, List<Patch> patchesMissing, Manifest baseManifest)
            throws Exception
    {
        int nextPossiblePatchVersion = Integer.parseInt(baseManifest.version) + 1;
        boolean foundNextManifestVersion = false;
        boolean createNewManifest = false;

        do {
            String newManifestFile = ServiceFactory.instance().getConfig().getNewManifestTemplatePath() +
                    Config.padManifestVersion(nextPossiblePatchVersion) + ".json";
            File newFile = new File(newManifestFile);
            if (newFile.exists()) {
                LOG.info("Manifest for patches already exists, will check if patches match " + newManifestFile);
                Manifest newManifest = ServiceFactory.instance().getJsonParser()
                        .fromJsonFile(newManifestFile, Manifest.class);
                List<Patch> patchesMissingInNew = validateWithManifest(availablePatches, newManifest);
                if (patchesMissingInNew.size() > 0) {
                    nextPossiblePatchVersion++;
                } else {
                    // this version has all the patches and is correct
                    foundNextManifestVersion = true;
                }
            } else {
                foundNextManifestVersion = true;
                createNewManifest = true;
            }
        } while(!foundNextManifestVersion);

        if (createNewManifest) {
            for (Patch patch : patchesMissing) {
                ManifestPath manifestPath = new ManifestPath();
                manifestPath.crc = patch.crcChecksum;
                manifestPath.v = patch.version;

                baseManifest.paths.put("patches/" + patch.patchName, manifestPath);
                baseManifest.paths.put("patches/" + patch.patchName + ".joe", manifestPath);
                baseManifest.paths.put("patches/" + patch.patchName + ".standalonewindows.assetbundle", manifestPath);
                baseManifest.paths.put("patches/" + patch.patchName + ".ios.assetbundle", manifestPath);
                baseManifest.paths.put("patches/" + patch.patchName + ".android.assetbundle", manifestPath);
            }

            String newManifestFile = ServiceFactory.instance().getConfig().getNewManifestTemplatePath() +
                    Config.padManifestVersion(nextPossiblePatchVersion) + ".json";
            LOG.info("Going to create manifest for patches " + newManifestFile);
            File newFile = new File(newManifestFile);
            if (!newFile.exists()) {
                baseManifest.version = Config.padManifestVersion(nextPossiblePatchVersion);
                String manifestJson = ServiceFactory.instance().getJsonParser().toJson(baseManifest);
                FileWriter myWriter = new FileWriter(newManifestFile);
                myWriter.write(manifestJson);
                myWriter.close();
            } else {
                throw new Exception("Manifest already exists but was expecting to create one " + newManifestFile);
            }
        }

        return nextPossiblePatchVersion;
    }

    private List<Patch> validateWithManifest(List<Patch> patchesWithJsonFile, Manifest baseManifest) {
        List<Patch> missingPatches = new ArrayList<>();
        for (Patch patch : patchesWithJsonFile) {
            ManifestPath manifestPath = baseManifest.paths.get("patches/" + patch.patchName);
            if (manifestPath != null) {
                // check to see if same version and CRC
                if (manifestPath.v != patch.version || !manifestPath.crc.equalsIgnoreCase(patch.crcChecksum))
                    manifestPath = null;
            }

            if (manifestPath == null)
                missingPatches.add(patch);
        }

        return missingPatches;
    }

    private List<Patch> validatePatchPath(List<Patch> patches) throws Exception {
        List<Patch> processedPatches = new ArrayList<>();
        String patchesPath = ServiceFactory.instance().getConfig().getAssetBundlePath();
        for (Patch patch : patches) {
            String patchFullPath = patchesPath + "/" + patch.version + "/patches/" + patch.patchName;
            File file = new File(patchFullPath);
            if (!file.exists()) {
                throw new Exception("Patch file " + patch.patchName + " has not been processed, could not find " + patchFullPath);
            }

            processedPatches.add(patch);
        }

        return processedPatches;
    }


    private void initialiseGameConstants() {
        this.pvpCosts = this.getPvpCosts();
        this.tournamentAttackerMedals = this.getTournamentAttackerMedals();
        this.tournamentDefenderMedals = this.getTournamentDefenderMedals();
    }

    @Override
    public int getTournamentAttackerMedals(int stars) {
        return this.tournamentAttackerMedals[stars];
    }

    @Override
    public int getTournamentDefenderMedals(int stars) {
        return this.tournamentDefenderMedals[stars];
    }

    private int[] getPvpCosts() {
        return getGameIntegerArrayFromString(this.gameConstants.pvp_search_cost_by_hq_level);
    }

    private int[] getGameIntegerArrayFromString(String gameConstantString) {
        String stringArray[] = gameConstantString.split(" ");
        int[] integerArray = new int[stringArray.length];
        for (int i = 0; i < stringArray.length; i++) {
            integerArray[i] = Integer.parseInt(stringArray[i]);
        }

        return integerArray;
    }

    private int[] getTournamentAttackerMedals() {
        int[] values = getGameIntegerArrayFromString(this.gameConstants.tournament_rating_deltas_attacker);
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i] * this.gameConstants.tournament_medal_scale;
        }
        return values;
    }

    private int[] getTournamentDefenderMedals() {
        int[] values = getGameIntegerArrayFromString(this.gameConstants.tournament_rating_deltas_defender);
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i] * this.gameConstants.tournament_medal_scale;
        }
        return values;
    }

    private FactionBuildingEquivalentMap create(Map<String, List<BuildingData>> buildingLevelsByBuildingId) {
        FactionBuildingEquivalentMap factionBuildingEquivalentMap = new FactionBuildingEquivalentMap();
        for (List<BuildingData> buildingLevels : buildingLevelsByBuildingId.values()) {
            factionBuildingEquivalentMap.addMap(buildingLevels);
        }

        return factionBuildingEquivalentMap;
    }

    private void buildCustomTroopMaps() {
        // list of creatures for faction for populating strix beacon
        this.lowestLevelTroopByUnitId.forEach((a, b) -> {
            if (b.getType() == TroopType.creature) {
                List<TroopData> creatureList = this.creaturesByFaction.get(b.getFaction());
                if (creatureList == null) {
                    creatureList = new ArrayList<>();
                    this.creaturesByFaction.put(b.getFaction(), creatureList);
                }
                creatureList.add(b);
            }
        });

        this.lowestLevelTroopByUnitId.forEach((a, b) -> {
            List<TroopData> troopList = this.lowestLevelTroopByFaction.get(b.getFaction());
            if (troopList == null) {
                troopList = new ArrayList<>();
                this.lowestLevelTroopByFaction.put(b.getFaction(), troopList);
            }
            troopList.add(b);
        });

        // map used to group troops by unit size for SC populating, does not include special attacks or champions
        this.lowestLevelTroopByUnitId.forEach((a, b) -> {
            if (!b.isSpecialAttack() && !(b.getType() == TroopType.champion)) {
                Map<Integer, List<TroopData>> troopBySizeMap = this.troopSizeMapByFaction.get(b.getFaction());
                if (troopBySizeMap == null) {
                    troopBySizeMap = new HashMap<>();
                    this.troopSizeMapByFaction.put(b.getFaction(), troopBySizeMap);
                }

                List<TroopData> troopBySize = troopBySizeMap.get(b.getSize());

                if (troopBySize == null) {
                    troopBySize = new ArrayList<>();
                    troopBySizeMap.put(b.getSize(), troopBySize);
                }

                troopBySize.add(b);
            }
        });
        // MAP FOR AVAILABLE UNIT CAPACITIES
        this.troopSizeMapByFaction.forEach((a, b) -> {
            Map<Integer, List<TroopData>> troopMapData = b;
            troopMapData.forEach((c, d) -> {
                List<Integer> troopsSizesAvailableByFaction = this.troopSizesAvailableByFaction.get(a);
                if (troopsSizesAvailableByFaction == null) {
                    troopsSizesAvailableByFaction = new ArrayList<>();
                    this.troopSizesAvailableByFaction.put(a, troopsSizesAvailableByFaction);
                }
                troopsSizesAvailableByFaction.add(c);
                troopsSizesAvailableByFaction.sort((o1, o2) -> Integer.compare(o1, o2));
            });

        });
    }

    private void loadCampaigns() throws Exception {
        CampaignFile result = ServiceFactory.instance().getJsonParser()
                .toObjectFromResource(ServiceFactory.instance().getConfig().caeJson, CampaignFile.class);

        for (CampaignData campaignData : result.content.objects.campaignData) {
            CampaignSet factionCampaignSet = this.factionCampaigns.get(campaignData.getFaction());
            if (factionCampaignSet == null) {
                factionCampaignSet = new CampaignSet(campaignData.getFaction());
                this.factionCampaigns.put(factionCampaignSet.getFactionType(), factionCampaignSet);
            }

            factionCampaignSet.addCampaignData(campaignData);
            this.campaignSets.put(campaignData.getUid(), factionCampaignSet);
        }

        for (CampaignMissionData campaignMissionData : result.content.objects.campaignMissionData) {
            CampaignSet campaignSet = this.campaignSets.get(campaignMissionData.getCampaignUid());
            CampaignMissionSet campaignMissionSet = campaignSet.getCampaignMissionSet(campaignMissionData.getCampaignUid());
            campaignMissionSet.addMission(campaignMissionData);
            this.campaignMissionSets.put(campaignMissionSet.getUid(), campaignMissionSet);
            this.missionDataMap.put(campaignMissionData.getUid(), campaignMissionData);
        }
    }

    @Override
    public TroopData getTroopDataByUid(String uid) {
        return this.troops.get(uid);
    }

    @Override
    public BuildingData getBuildingDataByUid(String uid) {
        return this.buildings.get(uid);
    }

    @Override
    public TrapData getTrapDataByUid(String uid) {
        return this.traps.get(uid);
    }

    private void loadTroops() throws Exception {
        this.troops.clear();

        Map result = ServiceFactory.instance().getJsonParser()
                .toObjectFromResource(ServiceFactory.instance().getConfig().troopJson, Map.class);
        Map<String, Map> jsonSpreadSheet = (Map<String, Map>) result;
        Map<String, Map> contentMap = jsonSpreadSheet.get("content");
        Map<String, Map> objectsMap = contentMap.get("objects");
        List<Map<String, String>> troopDataMap = (List<Map<String, String>>) objectsMap.get("TroopData");
        addTroopsToMap(troopDataMap);
        List<Map<String, String>> specialAttackDataMap = (List<Map<String, String>>) objectsMap.get("SpecialAttackData");
        addTroopsToMap(specialAttackDataMap);
    }

    private void addTroopsToMap(List<Map<String, String>> troopDataMap) {
        Set<String> unitIdsNeedsSorting = new HashSet<>();
        for (Map<String, String> troop : troopDataMap) {
            String faction = troop.get("faction");
            int lvl = Integer.valueOf(troop.get("lvl"));
            String uid = troop.get("uid");              //troopEmpireChicken1
            String unitId = troop.get("unitID");        //EmpireChicken
            String type = troop.get("type");
            int size = Integer.valueOf(troop.get("size")).intValue();
            long trainingTime = Long.valueOf(troop.get("trainingTime")).longValue();
            long upgradeTime = Long.valueOf(troop.get("upgradeTime")).longValue();
            String upgradeShardUid = troop.get("upgradeShardUid");
            int upgradeShards = Integer.valueOf(troop.get("upgradeShards") == null ? "0" : troop.get("upgradeShards")).intValue();
            String specialAttackID = troop.get("specialAttackID");
            int credits = Integer.valueOf(troop.get("credits") == null ? "0" : troop.get("credits"));
            int materials = Integer.valueOf(troop.get("materials") == null ? "0" : troop.get("materials"));
            int contraband = Integer.valueOf(troop.get("contraband") == null ? "0" : troop.get("contraband"));
            int upgradeCredits = Integer.valueOf(troop.get("upgradeCredits") == null ? "0" : troop.get("upgradeCredits"));
            int upgradeMaterials = Integer.valueOf(troop.get("upgradeMaterials") == null ? "0" : troop.get("upgradeMaterials"));
            int upgradeContraband = Integer.valueOf(troop.get("upgradeContraband") == null ? "0" : troop.get("upgradeContraband"));

            TroopData troopData = new TroopData(uid);
            troopData.setFaction(FactionType.valueOf(faction));
            troopData.setLevel(lvl);
            troopData.setUnitId(unitId != null ? unitId : specialAttackID);
            troopData.setType(type != null ? TroopType.valueOf(type) : null);
            troopData.setSize(size);
            troopData.setTrainingTime(trainingTime);
            troopData.setUpgradeTime(upgradeTime);
            troopData.setUpgradeShardUid(upgradeShardUid);
            troopData.setUpgradeShards(upgradeShards);
            troopData.setSpecialAttackID(specialAttackID);
            troopData.setCredits(credits);
            troopData.setMaterials(materials);
            troopData.setContraband(contraband);
            troopData.setUpgradeCredits(upgradeCredits);
            troopData.setUpgradeMaterials(upgradeMaterials);
            troopData.setUpgradeContraband(upgradeContraband);

            this.troops.put(troopData.getUid(), troopData);
            addToLowestLevelTroopUnitId(troopData);

            String needToSort = addToLevelMaps(troopData);
            if (needToSort != null)
                unitIdsNeedsSorting.add(needToSort);
        }

        unitIdsNeedsSorting.forEach(a -> this.troopLevelsByUnitId.get(a)
                .sort((b, c) -> Integer.compare(b.getLevel(), c.getLevel())));
    }

    private String addToLevelMaps(TroopData troopData) {
        String unitId = troopData.getUnitId();
        if (unitId != null) {
            List<TroopData> levels = this.troopLevelsByUnitId.get(unitId);
            if (levels == null) {
                levels = new ArrayList<>();
                this.troopLevelsByUnitId.put(unitId, levels);
            }

            // this list will be sorted later before it can be used
            levels.add(troopData);
        }

        return unitId;
    }

    private void addToLowestLevelTroopUnitId(TroopData troopData) {
        TroopData currentLowest = this.lowestLevelTroopByUnitId.get(troopData.getUnitId());
        if (currentLowest == null) {
            this.lowestLevelTroopByUnitId.put(troopData.getUnitId(), troopData);
        } else {
            if (troopData.getLevel() < currentLowest.getLevel()) {
                this.lowestLevelTroopByUnitId.put(troopData.getUnitId(), troopData);
            }
        }
    }

    private Map<String, TrapData> loadTraps() throws Exception {
        Map<String, TrapData> map = new HashMap<>();
        Map result = ServiceFactory.instance().getJsonParser()
                .toObjectFromResource(ServiceFactory.instance().getConfig().baseJson, Map.class);
        Map<String, Map> jsonSpreadSheet = (Map<String, Map>) result;
        Map<String, Map> contentMap = jsonSpreadSheet.get("content");
        Map<String, Map> objectsMap = contentMap.get("objects");
        List<Map<String, String>> trapDataMap = (List<Map<String, String>>) objectsMap.get("TrapData");
        addTrapsToMap(map, trapDataMap);
        return map;
    }

    private void addTrapsToMap(Map<String, TrapData> map, List<Map<String, String>> trapDataMap) {
        for (Map<String, String> trap : trapDataMap) {
            String uid = trap.get("uid");
            TrapEventType trapEventType = TrapEventType.valueOf(trap.get("eventType"));
            long rearmTime = trap.get("rearmTime") != null ? Long.valueOf(trap.get("rearmTime")) : 0;
            int rearmMaterialsCost = trap.get("rearmMaterialsCost") != null ? Integer.valueOf(trap.get("rearmMaterialsCost")) : 0;
            String eventData = trap.get("eventData");
            TrapData trapData = new TrapData(uid);
            trapData.setEventType(trapEventType);
            trapData.setRearmTime(rearmTime);
            trapData.setEventData(eventData);
            trapData.setRearmMaterialsCost(rearmMaterialsCost);
            map.put(trapData.getUid(), trapData);
        }
    }

    private void loadBaseJsonAndGameConstants() throws Exception {
        Map result = ServiceFactory.instance().getJsonParser()
                .toObjectFromResource(ServiceFactory.instance().getConfig().baseJson, Map.class);
        Map<String, Map> jsonSpreadSheet = (Map<String, Map>) result;
        Map<String, Map> contentMap = jsonSpreadSheet.get("content");
        Map<String, Map> objectsMap = contentMap.get("objects");
        List<Map<String, String>> buildingDataMap = (List<Map<String, String>>) objectsMap.get("BuildingData");
        this.buildings = readBuildingData(buildingDataMap);

        List<Map<String, String>> gameConstants = (List<Map<String, String>>) objectsMap.get("GameConstants");
        this.gameConstants = readGameConstants(gameConstants);
    }

    private GameConstants readGameConstants(List<Map<String, String>> gameConstants) throws Exception {
        GameConstants constants = GameConstants.createFromBaseJson(gameConstants);
        return constants;
    }

    private Map<String, BuildingData> readBuildingData(List<Map<String, String>> buildingDataMap) {
        Map<String, BuildingData> map = new HashMap<>();

        Set<String> buildingIdsNeedsSorting = new HashSet<>();

        for (Map<String, String> building : buildingDataMap) {
            String faction = building.get("faction");
            String uid = building.get("uid");
            String buildingID = building.get("buildingID");
            String type = building.get("type");
            String trapId = building.get("trapID");
            int lvl = Integer.valueOf(building.get("lvl"));
            int storage = Integer.valueOf(building.get("storage") == null ? "0" : building.get("storage")).intValue();
            int time = Integer.valueOf(building.get("time")).intValue();
            int crossTime = Integer.valueOf(building.get("crossTime") == null ? "0" : building.get("crossTime")).intValue();
            int crossCredits = Integer.valueOf(building.get("crossCredits") == null ? "0" : building.get("crossCredits")).intValue();
            int crossMaterials = Integer.valueOf(building.get("crossMaterials") == null ? "0" : building.get("crossMaterials")).intValue();
            int materials = Integer.valueOf(building.get("materials") == null ? "0" : building.get("materials")).intValue();
            int credits = Integer.valueOf(building.get("credits") == null ? "0" : building.get("credits")).intValue();
            int contraband = Integer.valueOf(building.get("contraband") == null ? "0" : building.get("contraband")).intValue();
            String currency = building.get("currency") != null ? building.get("currency") : "none";
            String linkedUnit = building.get("linkedUnit");
            StoreTab storeTab = building.get("storeTab") != null ? StoreTab.valueOf(building.get("storeTab")) : null;
            BuildingSubType buildingSubType = building.get("subType") != null ?
                    BuildingSubType.valueOf(building.get("subType")) : null;
            float produce = Float.valueOf(building.get("produce") != null ? building.get("produce") : "0");
            float cycleTime = Float.valueOf(building.get("cycleTime") != null ? building.get("cycleTime") : "0");
            boolean prestige = Boolean.valueOf(building.get("prestige") != null ? building.get("prestige") : "false");
            int xp = Integer.valueOf(building.get("xp") == null ? "0" : building.get("xp")).intValue();


            BuildingData buildingData = new BuildingData(uid);
            buildingData.setFaction(FactionType.valueOf(faction));
            buildingData.setLevel(lvl);
            buildingData.setType(BuildingType.valueOf(type));
            buildingData.setCurrency(CurrencyType.valueOf(currency.equals("0") ? "none" : currency));
            buildingData.setProduce(produce);
            buildingData.setCycleTime(cycleTime);
            buildingData.setStorage(storage);
            buildingData.setTime(time);
            buildingData.setCrossTime(crossTime);
            buildingData.setCrossCredits(crossCredits);
            buildingData.setCrossMaterials(crossMaterials);
            buildingData.setBuildingID(buildingID);
            buildingData.setTrapId(trapId);
            buildingData.setLinkedUnit(linkedUnit);
            buildingData.setStoreTab(storeTab);
            buildingData.setSubType(buildingSubType);
            buildingData.setMaterials(materials);
            buildingData.setCredits(credits);
            buildingData.setContraband(contraband);
            buildingData.setPrestige(prestige);
            buildingData.setXp(xp); //used to calculate base scores


            map.put(buildingData.getUid(), buildingData);
            String needToSort = addToLevelMaps(buildingData);
            if (needToSort != null)
                buildingIdsNeedsSorting.add(needToSort);
        }

        buildingIdsNeedsSorting.forEach(a -> this.buildingLevelsByBuildingId.get(a)
                .sort((b, c) -> Integer.compare(b.getLevel(), c.getLevel())));
        return map;
    }

    private String addToLevelMaps(BuildingData buildingData) {
        String buildingID = buildingData.getBuildingID();
        if (buildingID != null) {
            List<BuildingData> levels = this.buildingLevelsByBuildingId.get(buildingID);
            if (levels == null) {
                levels = new ArrayList<>();
                this.buildingLevelsByBuildingId.put(buildingID, levels);
            }

            // this list will be sorted later before it can be used
            levels.add(buildingData);
        }

        return buildingID;
    }

    @Override
    public TroopData getLowestLevelTroopDataByUnitId(String unitId) {
        return this.lowestLevelTroopByUnitId.get(unitId);
    }

    @Override
    public TroopData getTroopDataByUnitId(String unitId, int level) {
        if (this.troopLevelsByUnitId.get(unitId).size() < (level - 1))
            return null;

        return this.troopLevelsByUnitId.get(unitId).get(level - 1);
    }

    @Override
    public BuildingData getBuildingDataByBuildingId(String buildingID, int level) {
        List<BuildingData> buildingLevels = this.buildingLevelsByBuildingId.get(buildingID);
        if (buildingLevels != null) {
            if (level <= buildingLevels.size())
                return buildingLevels.get(level - 1);

            LOG.error("Failed to find buildingLevels for " + buildingID + " at level " + level);
        } else {
            LOG.error("Failed to find buildingLevels for " + buildingID);
        }

        return null;
    }

    @Override
    public CampaignMissionData getCampaignMissionData(String missionUid) {
        return this.missionDataMap.get(missionUid);
    }

    @Override
    public CampaignSet getCampaignForFaction(FactionType faction) {
        return this.factionCampaigns.get(faction);
    }

    @Override
    public CampaignMissionSet getCampaignMissionSet(String campaignUid) {
        return this.campaignMissionSets.get(campaignUid);
    }

    @Override
    public List<TroopData> getLowestLevelTroopsForFaction(FactionType faction) {
        return this.lowestLevelTroopByFaction.get(faction);
    }

    @Override
    public Map<FactionType, List<TroopData>> getCreaturesByFaction() {
        return creaturesByFaction;
    }

    @Override
    public Map<Integer, List<TroopData>> getTroopSizeMap(FactionType faction) {
        return this.troopSizeMapByFaction.get(faction);
    }

    @Override
    public int getMaxlevelForTroopUnitId(String unitId) {
        return this.troopLevelsByUnitId.get(unitId).size();
    }

    @Override
    public GameConstants getGameConstants() {
        return this.gameConstants;
    }

    /**
     * This is only used when changing faction
     *
     * @param oldBuildingData
     * @param targetFaction
     * @return
     */
    @Override
    public BuildingData getFactionEquivalentOfBuilding(BuildingData oldBuildingData, FactionType targetFaction) {
        return this.factionBuildingEquivalentMap.getEquivalentBuilding(oldBuildingData, targetFaction);
    }


    @Override
    public int getPvpMatchCost(int hQLevel) {
        return this.pvpCosts[hQLevel - 1];
    }

    private void setupDevBases() {
        if (!ServiceFactory.instance().getConfig().loadDevBases) {
            return;
        }

        try {
            List<File> layouts = listFiles(ServiceFactory.instance().getConfig().layoutsPath);
            for (File layoutFile : layouts) {
                Buildings mapObject = ServiceFactory.instance().getJsonParser()
                        .fromJsonFile(layoutFile.getAbsolutePath(), Buildings.class);

                int xp = ServiceFactory.getXpFromBuildings(mapObject);
                int hq = 0;
                for (Building building : mapObject) {
                    BuildingData buildingData = ServiceFactory.instance().getGameDataManager()
                            .getBuildingDataByUid(building.uid);

                    if (buildingData != null) {
                        switch (buildingData.getType()) {
                            case trap: //fill trap
                                building.currentStorage = 1;
                                break;
                            case HQ:
                                hq = buildingData.getLevel();
                                break;
                        }
                    }

                    // make it a neutral map
                    building.uid = building.uid.replace(FactionType.rebel.name(), FactionType.neutral.name())
                            .replace(FactionType.empire.name(), FactionType.neutral.name());
                }

                DevBase devBase = new DevBase();
                devBase.fileName = layoutFile.getAbsolutePath();
                devBase.buildings = mapObject;
                devBase.hq = hq;
                devBase.xp = xp;
                devBase.checksum = getCRC32Checksum(mapObject);
                ServiceFactory.instance().getPlayerDatasource().saveDevBase(devBase);
            }
            LOG.info("Finished setting up dev bases");
        } catch (Exception ex) {
            LOG.error("Failed to load next layout", ex);
        }
    }

    public static long getCRC32Checksum(Serializable object) throws IOException {
        byte[] bytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            bytes = bos.toByteArray();
        }
        Checksum crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    public static long getCRC32Checksum(InputStream in) throws IOException {
        Checksum crcMaker = new CRC32();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while((bytesRead = in.read(buffer)) != -1) {
            crcMaker.update(buffer, 0, bytesRead);
        }
        long crc = crcMaker.getValue(); // This is your error checking code
        return crc;
    }

    static public List<File> listFiles(String directoryName) {
        File directory = new File(directoryName);
        List<File> resultList = new ArrayList<>();
        // get all the files from a directory
        File[] fList = directory.listFiles();
        for (File file : fList) {
            if (file.isDirectory()) {
                resultList.addAll(listFiles(file.getAbsolutePath()));
            } else if (file.getAbsolutePath().toLowerCase().endsWith(".json")) {
                resultList.add(file);
            }
        }
        return resultList;
    }

    @Override
    public List<Integer> getTroopSizesAvailable(FactionType faction) {
        return this.troopSizesAvailableByFaction.get(faction);
    }

    @Override
    public String randomDevBaseName() {
        //TODO, make this random based on some stored values.... I have a cunning plan for this, Baldrick
        return "DEV BASE";
    }

    @Override
    public Map<String,Integer> remapTroopUidToUnitId(Map<String, Integer> troopUids) {
        Map<String,Integer> remapped = new HashMap<>();
        if (troopUids != null) {
            troopUids.forEach((a, b) -> remapped.put(this.getTroopDataByUid(a).getUnitId(), b));
        }
        return remapped;
    }

    @Override
    public TroopData getTroopByUnitId(Troops troops, String unitId) {
        TroopRecord troopRecord = troops.getTroops().get(unitId);

        int level = 1;
        if (troopRecord != null)
            level = troopRecord.getLevel();

        return getTroopDataByUnitId(unitId, level);
    }

    @Override
    public void setPatchesAvailable(List<Patch> availablePatches) {
        this.availablePatches = availablePatches;
    }

    @Override
    public List<Patch> getPatchesAvailable() {
        return this.availablePatches;
    }

    @Override
    public ConflictManager getConflictManager() {
        return this.conflictManager;
    }
}
