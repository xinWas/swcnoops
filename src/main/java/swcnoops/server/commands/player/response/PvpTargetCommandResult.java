package swcnoops.server.commands.player.response;

import swcnoops.server.model.*;
import swcnoops.server.requests.AbstractCommandResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PvpTargetCommandResult extends AbstractCommandResult {
    public String battleId;
    public String playerId;
    public String name;
    public int level;
    public int xp;
    public FactionType faction;

    public int attackRating;
    public int defenseRating;
    public int attacksWon;
    public int defensesWon;

    public String guildId;
    public String guildName;

    public PlayerMap map;
    public DonatedTroops guildTroops;
    public Map<String, Map<CurrencyType, Integer>> resources;

    public Map<String, Integer> champions;
    public List<CreatureTrapData> creatureTrapData;

    public Map<String, Integer> potentialPoints;

//    public int potentialMedalsToGain;
//    public int potentialMedalsToLose;
    //    public int potentialTournamentRatingDeltaWin;
//    public int potentialTournamentRatingDeltaLose;
//    public int availableCredits;
//    public int availableMaterials;
//    public int availableContraband;
//    public Object buildingLootCreditsMap;
//    public Object buildingLootMaterialsMap;
//    public Object buildingLootContrabandMap;
//    public Object attackerDeployables;
    public int creditsCharged;
    public Object contracts;
    public List<String> equipment = new ArrayList<>();
}
