package swcnoops.server.game;

import swcnoops.server.ServiceFactory;
import swcnoops.server.datasource.TournamentLeaderBoard;
import swcnoops.server.datasource.TournamentStat;
import swcnoops.server.datasource.buffers.RingBuffer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConflictManagerImpl implements ConflictManager {
    private volatile List<TournamentData> conflicts = new ArrayList<>();
    private Map<String, TournamentData> planetConflicts = new ConcurrentHashMap<>();
    private Map<String, TournamentData> tournaments = new ConcurrentHashMap<>();
    private TournamentTierData topTier;

    public void setup(List<TournamentData> validConflicts) {
        long now = ServiceFactory.getSystemTimeSecondsFromEpoch();

        if (validConflicts != null) {
            // sort them in order
            validConflicts.sort((a,b) -> Long.compare(a.getStartTime(), b.getStartTime()));
            this.conflicts = new ArrayList<>(validConflicts);
            for (TournamentData tournamentData : this.conflicts) {
                if (tournamentData.isActive(now)) {
                    this.planetConflicts.put(tournamentData.planetId, tournamentData);
                    this.tournaments.put(tournamentData.getUid(), tournamentData);
                }
            }
        }

        List<String> planetKeys = new ArrayList<>(this.planetConflicts.keySet());
        for (String planetId : planetKeys) {
            TournamentData tournamentData = this.planetConflicts.get(planetId);
            if (tournamentData != null) {
                if (tournamentData.hasExpired(now)) {
                    this.planetConflicts.remove(planetId);
                }
            }
        }
    }

    @Override
    public TournamentData getConflict(String planetId) {
        return this.planetConflicts.get(planetId);
    }

    public void setTopTier(TournamentTierData topTier) {
        this.topTier = topTier;
    }

    public TournamentTierData getTopTier() {
        return topTier;
    }

    @Override
    public void calculatePercentile(TournamentLeaderBoard leaderBoard) {
        if (leaderBoard != null) {
            calculatePercentile(leaderBoard.getTop50(), leaderBoard.getLastTournamentStat());
            calculatePercentile(leaderBoard.getSurroundingMe(), leaderBoard.getLastTournamentStat());
        }
    }

    @Override
    public TournamentStat getTournamentStats(List<TournamentStat> tournaments, TournamentData tournamentData) {
        TournamentStat tournamentStat = null;
        if (tournaments != null && tournamentData != null) {
            for (TournamentStat stat : tournaments) {
                if (stat.uid.equals(tournamentData.getUid())) {
                    tournamentStat = stat;
                    break;
                }
            }
        }

        return tournamentStat;
    }

    @Override
    public TournamentData getTournament(String uid) {
        return tournaments.get(uid);
    }

    private void calculatePercentile(RingBuffer top50, TournamentStat lastTournamentStat) {
        if (lastTournamentStat != null) {
            float maxRank = calculateMaxRank(lastTournamentStat);

            Iterator<TournamentStat> statIterator = top50.iterator();
            while (statIterator.hasNext()) {
                TournamentStat stat = statIterator.next();
                calculatePercentile(stat, maxRank);
            }
        }
    }

    @Override
    public void calculatePercentile(TournamentStat foundPlayer, TournamentStat lastTournamentStat) {
        if (lastTournamentStat != null) {
            float maxRank = calculateMaxRank(lastTournamentStat);
            calculatePercentile(foundPlayer, maxRank);
        }
    }

    private float calculateMaxRank(TournamentStat lastTournamentStat) {
        float maxRank = lastTournamentStat.rank;
        float maxTierRank = 100 / this.getTopTier().percentage;
        maxRank = Math.max(maxTierRank, maxRank);
        return maxRank;
    }

    private void calculatePercentile(TournamentStat foundPlayer, float maxRank) {
        if (foundPlayer != null)
            foundPlayer.percentile = 100 - (100 * (foundPlayer.rank / maxRank));
    }
}
