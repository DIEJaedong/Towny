package com.palmergames.bukkit.towny.utils;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyFormatter;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.*;
import com.palmergames.bukkit.towny.object.*;
import com.palmergames.bukkit.towny.permissions.PermissionNodes;
import com.palmergames.bukkit.towny.war.siegewar.Siege;
import com.palmergames.bukkit.towny.war.siegewar.SiegeStatus;
import com.palmergames.bukkit.towny.war.siegewar.SiegeZone;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.bukkit.util.ChatTools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.potion.PotionEffectType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by Goosius on 19/05/2019.
 */
public class SiegeWarUtil {

    public final static long ONE_SECOND_IN_MILLIS = 1000;
    public final static long ONE_MINUTE_IN_MILLIS = ONE_SECOND_IN_MILLIS * 60;
    public final static long ONE_HOUR_IN_MILLIS = ONE_MINUTE_IN_MILLIS * 60;
    public final static long ONE_DAY_IN_MILLIS = ONE_HOUR_IN_MILLIS * 24;

    public static void attackTown(Block block,
                                  Nation attackingNation,
                                    Town defendingTown) throws TownyException {

        Siege siege;
        SiegeZone siegeZone;
        boolean newSiege;

        if(!defendingTown.hasSiege()) {
            newSiege = true;

            //Create Siege
            siege = new Siege(defendingTown);
            siege.setStatus(SiegeStatus.IN_PROGRESS);
            siege.setTownPlundered(false);
            siege.setTownInvaded(false);
            siege.setAttackerWinner(null);
            siege.setStartTime(System.currentTimeMillis());
            siege.setScheduledEndTime(
                    (System.currentTimeMillis() +
                    ((long)(TownySettings.getWarSiegeMaxHoldoutTimeHours() * ONE_HOUR_IN_MILLIS))));
            siege.setActualEndTime(0);
            siege.setNextUpkeepTime(System.currentTimeMillis() + ONE_MINUTE_IN_MILLIS);
            defendingTown.setSiege(siege);

        } else {
            //Get Siege
            newSiege = false;
            siege = defendingTown.getSiege();
        }

        //Create siege zone
        TownyUniverse.getDataSource().newSiegeZone(
                attackingNation.getName(),
                defendingTown.getName());
        siegeZone = TownyUniverse.getDataSource().getSiegeZone(
                attackingNation.getName(),
                defendingTown.getName());
        siegeZone.setActive(true);

        siegeZone.setFlagLocation(block.getLocation());
        siege.getSiegeZones().put(attackingNation, siegeZone);
        attackingNation.addSiegeZone(siegeZone);

        //Save siegezone, siege, nation, and town to DB
        TownyUniverse.getDataSource().saveSiegeZone(siegeZone);
        TownyUniverse.getDataSource().saveNation(attackingNation);
        TownyUniverse.getDataSource().saveTown(defendingTown);
        TownyUniverse.getDataSource().saveSiegeZoneList();

        //Send global message;
        if(newSiege) {
            TownyMessaging.sendGlobalMessage(
                    "The nation of " + attackingNation.getName() +
                            " has initiated a siege on the town of " + defendingTown.getName());
        } else {
            TownyMessaging.sendGlobalMessage(
                    "The nation of " + attackingNation.getName() +
                            " has joined the siege on the town of " + defendingTown.getName());
        }


        //BukkitTools.getPluginManager().callEvent(new NewNationEvent(nation));
        //TODO - Do we announce a new siege event like this???
    }

    public static void processAbandonSiegeRequest(Player player,
                                                  Block block,
                                                  List<TownBlock> nearbyTownBlocksWithTowns,
                                                  BlockPlaceEvent event)  {
        try {
            //If player has no permission to abandon,send error
            if (!TownyUniverse.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_NATION_SIEGE_ABANDON.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            //Player has no town
            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());
            if(!resident.hasTown())
                throw new TownyException("You cannot place an abandon banner because you do not belong to a town.");

            if(!resident.getTown().hasNation())
                throw new TownyException("You cannot place an abandon banner because you do not belong to a nation.");

            //Get list of adjacent towns with sieges
            List<Town> nearbyTownsWithSieges =new ArrayList<>();
            for(TownBlock nearbyTownBlock: nearbyTownBlocksWithTowns) {
                if(nearbyTownBlock.getTown().hasSiege()
                        && nearbyTownBlock.getTown().getSiege().getStatus() == SiegeStatus.IN_PROGRESS){
                    nearbyTownsWithSieges.add(nearbyTownBlock.getTown());
                }
            }

            //If none are under active siege, send error
            if(nearbyTownsWithSieges.size() == 0)
                throw new TownyException("You cannot place an abandon banner because none of the nearby towns are under siege.");

            //Get the active siege zones
            List<SiegeZone> nearbyActiveSiegeZones = new ArrayList<>();
            for(Town nearbyTownWithSiege: nearbyTownsWithSieges) {
                for(SiegeZone siegeZone: nearbyTownWithSiege.getSiege().getSiegeZones().values()) {
                    if(siegeZone.isActive())
                        nearbyActiveSiegeZones.add(siegeZone);
                }
            }

            //Find the nearest active zone to the player
            SiegeZone targetedSiegeZone = null;
            double distanceToTarget = -1;
            for(SiegeZone siegeZone: nearbyActiveSiegeZones) {
                if (targetedSiegeZone == null) {
                    targetedSiegeZone = siegeZone;
                    distanceToTarget = block.getLocation().distance(targetedSiegeZone.getFlagLocation());
                } else {
                    double distanceToNewTarget = block.getLocation().distance(siegeZone.getFlagLocation());
                    if(distanceToNewTarget < distanceToTarget) {
                        targetedSiegeZone = siegeZone;
                        distanceToTarget = distanceToNewTarget;
                    }
                }
            }

            //If the player's nation is not the attacker, send error
            Nation residentNation = resident.getTown().getNation();
            if(targetedSiegeZone.getAttackingNation() != residentNation)
                throw new TownyException("Your nation is not attacking this town right now");

            //If the player is too far from the targeted zone, error error
            if(distanceToTarget > TownySettings.getTownBlockSize())
                throw new TownyException("You cannot place an abandon banner because " +
                        "you are too far from the nearest attack banner. " +
                        "Move closer to the attack banner");

            SiegeWarUtil.attackerAbandon(targetedSiegeZone);

        } catch (TownyException x) {
            TownyMessaging.sendErrorMsg(player, x.getMessage());
            event.setCancelled(true);
        }
    }

    public static void processSurrenderRequest(Player player,
                                               Town townWhereBlockWasPlaced,
                                               BlockPlaceEvent event) {

        try {
            if (!TownySettings.getWarSiegeEnabled())
                throw new TownyException("Siege war feature disabled");  //todo - replace w lang string

            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());
            if(!resident.hasTown())
                throw new TownyException("You cannot place a surrender banner because you do not belong to a town.");

            if(resident.getTown() != townWhereBlockWasPlaced)
                throw new TownyException("You cannot place a surrender banner because this is not your town.");

            if (!TownyUniverse.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_TOWN_SIEGE_SURRENDER.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            Siege siege = townWhereBlockWasPlaced.getSiege();
            if(siege.getStatus() != SiegeStatus.IN_PROGRESS)
                throw new TownyException("You cannot place a surrender banner because the siege is over");

            if(siege.getActiveAttackers().size() > 1)
                throw new TownyException("You cannot place a surrender banner if there is more than 1 attacker");

            //Surrender
            SiegeWarUtil.defenderSurrender(siege);

        } catch (TownyException x) {
            TownyMessaging.sendErrorMsg(player, x.getMessage());
            event.setCancelled(true);
        }
    }

    private static boolean doesPlayerHaveANonAirBlockAboveThem(Player player) {
        return doesLocationHaveANonAirBlockAboveIt(player.getLocation());
    }

    private static boolean doesBlockHaveANonAirBlockAboveIt(Block block) {
        return doesLocationHaveANonAirBlockAboveIt(block.getLocation());
    }

    private static boolean doesLocationHaveANonAirBlockAboveIt(Location location) {
        location = location.add(0,1,0);

        while(location.getY() < 256)
        {
            if(location.getBlock().getType() != Material.AIR)
            {
                return true;   //There is a non-air block above them
            }
            location.add(0,1,0);
        }
        return false;  //There is nothing but air above them
    }



    private static void captureTown(Towny plugin, Siege siege, Nation attackingNation, Town defendingTown) {
        siege.setTownInvaded(true);

        if(defendingTown.hasNation()) {
            Nation nationOfDefendingTown = null;
            try {
                nationOfDefendingTown = defendingTown.getNation();
            } catch (NotRegisteredException x) {
                //This won't happen because we checked for a nation just above
            }

            //Remove town from nation (and nation itself if empty)
            removeTownFromNation(plugin, defendingTown, nationOfDefendingTown);

            addTownToNation(plugin, defendingTown, attackingNation);

            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_nation_town_captured"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(nationOfDefendingTown),
                    TownyFormatter.getFormattedNationName(attackingNation)
            )));

            if(nationOfDefendingTown.getTowns().size() == 0) {
                TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                        TownySettings.getLangString("msg_siege_war_nation_defeated"),
                        TownyFormatter.getFormattedNationName(nationOfDefendingTown)
                )));
            }
        } else {
            addTownToNation(plugin, defendingTown, attackingNation);

            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_neutral_town_captured"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(attackingNation)
            )));
        }
    }

    private static void removeTownFromNation(Towny plugin, Town town, Nation nation) {
        boolean removeNation = false;

        try {
            nation.removeTown(town);
        } catch(NotRegisteredException x) {
            TownyMessaging.sendErrorMsg("Attempted to remove town from nation but Town was already removed.");
            return;  //Town was already removed
        } catch(EmptyNationException x) {
            removeNation = true;  //Set flag to remove nation at end of this method
        }
        /*
         * Remove all resident titles/nationRanks before saving the town itself.
         */
        List<Resident> titleRemove = new ArrayList<Resident>(town.getResidents());

        for (Resident res : titleRemove) {
            if (res.hasTitle() || res.hasSurname()) {
                res.setTitle("");
                res.setSurname("");
            }
            res.updatePermsForNationRemoval(); // Clears the nationRanks.
            TownyUniverse.getDataSource().saveResident(res);
        }

        if(removeNation) {
            TownyUniverse.getDataSource().removeNation(nation);
            TownyUniverse.getDataSource().saveNationList();
        } else {
            TownyUniverse.getDataSource().saveNation(nation);
            TownyUniverse.getDataSource().saveNationList();
            plugin.resetCache();
        }

        TownyUniverse.getDataSource().saveTown(town);
    }


    private static void addTownToNation(Towny plugin, Town town,Nation nation) {
        try {
            nation.addTown(town);
            TownyUniverse.getDataSource().saveTown(town);
            plugin.resetCache();
            TownyUniverse.getDataSource().saveNation(nation);
        } catch (AlreadyRegisteredException x) {
            return;   //Town already in nation
        }
    }


    private static void plunderTown(Siege siege, Town defendingTown, Nation winnerNation) {
        siege.setTownPlundered(true);

        double plunder =
                TownySettings.getWarSiegeAttackerPlunderAmountPerPlot()
                        * defendingTown.getTownBlocks().size();
        try {
            if (defendingTown.canPayFromHoldings(plunder)) {
                defendingTown.payTo(plunder, winnerNation, "Town was plundered by attacker");
                sendPlunderSuccessMessage(defendingTown, winnerNation);
        } else {
                double actualPlunder = defendingTown.getHoldingBalance();
                defendingTown.payTo(actualPlunder, winnerNation, "Town was plundered by attacker");
                sendPlunderSuccessMessage(defendingTown, winnerNation);
                TownyMessaging.sendGlobalMessage("The town " + defendingTown.getName() + " was destroyed by " +winnerNation.getName());
                TownyUniverse.getDataSource().removeTown(defendingTown);
            }
        } catch (EconomyException x) {
            TownyMessaging.sendErrorMsg(x.getMessage());
        }
    }


    private static void sendPlunderSuccessMessage(Town defendingTown, Nation winnerNation) {
        if(defendingTown.hasNation()) {
            try {
                TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                        TownySettings.getLangString("msg_siege_war_nation_town_plundered"),
                        TownyFormatter.getFormattedTownName(defendingTown),
                        TownyFormatter.getFormattedNationName(defendingTown.getNation()),
                        TownyFormatter.getFormattedNationName(winnerNation)
                )));
            } catch (NotRegisteredException e) {
                e.printStackTrace();
            }
        } else {
            TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                    TownySettings.getLangString("msg_siege_war_neutral_town_plundered"),
                    TownyFormatter.getFormattedTownName(defendingTown),
                    TownyFormatter.getFormattedNationName(winnerNation))));
        }
    }

    public static void applySiegeUpkeepCost(Siege siege) {
        //TODO - REFACTOR TO BE MORE EFFICIENT
        //E.G CYCLE THE ZONES DIRECTLY
        double upkeepCostPerPlot = TownySettings.getWarSiegeAttackerCostPerPlotPerHour();
        long upkeepCost = Math.round(upkeepCostPerPlot * siege.getDefendingTown().getTotalBlocks());

        //Each attacking nation who is involved must pay upkeep
        if(upkeepCost > 1) {
            for (Nation nation : siege.getSiegeZones().keySet()) {
                try {
                    if (nation.canPayFromHoldings(upkeepCost))
                        nation.pay(upkeepCost, "Cost of maintaining siege.");
                    else {
                        siege.getSiegeZones().get(nation).setActive(false);
                        TownyUniverse.getDataSource().saveSiegeZone( siege.getSiegeZones().get(nation));
                        TownyMessaging.sendGlobalMessage("The nation of " + nation.getName() +
                                " has been forced to abandon the siege on the town of " + siege.getDefendingTown().getName() +
                                ", due to lack of funds.");
                    }
                } catch (EconomyException x) {
                    TownyMessaging.sendErrorMsg(x.getMessage());
                }
            }
        }
    }

    private static void attackerWin(Siege siege, Nation winnerNation) {
        updateAndSaveSiegeCompletionValues(siege, SiegeStatus.ATTACKER_WIN, winnerNation);

        TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                TownySettings.getLangString("msg_siege_war_attacker_win"),
                TownyFormatter.getFormattedNationName(winnerNation),
                TownyFormatter.getFormattedTownName(siege.getDefendingTown()))
        ));
    }

    private static void attackerAbandon(SiegeZone siegeZone) {
        siegeZone.setActive(false);
        TownyUniverse.getDataSource().saveSiegeZone(siegeZone);

        TownyMessaging.sendGlobalMessage(siegeZone.getAttackingNation().getName() + " has abandoned their attack on" + siegeZone.getDefendingTown().getName());

        if (siegeZone.getSiege().getActiveAttackers().size() == 0) {
            updateAndSaveSiegeCompletionValues(siegeZone.getSiege(),
                    SiegeStatus.ATTACKER_ABANDON,
                    null);
            TownyMessaging.sendGlobalMessage("The siege on " + siegeZone.getDefendingTown().getName() +" has been abandoned all attackers.");
        }
    }

    private static void defenderWin(Siege siege, Town winnerTown) {
        updateAndSaveSiegeCompletionValues(siege, SiegeStatus.DEFENDER_WIN, null);

        TownyMessaging.sendGlobalMessage(ChatTools.color(String.format(
                TownySettings.getLangString("msg_siege_war_defender_win"),
                TownyFormatter.getFormattedTownName(winnerTown)
        )));
    }

    private static void defenderSurrender(Siege siege) throws TownyException {
        updateAndSaveSiegeCompletionValues(siege,
                                            SiegeStatus.DEFENDER_SURRENDER,
                                            siege.getActiveAttackers().get(0));

        TownyMessaging.sendGlobalMessage("Town has surrendered.");
    }

    private static void updateAndSaveSiegeCompletionValues(Siege siege,
                                                           SiegeStatus siegeStatus,
                                                           Nation winnerNation) {
        siege.setStatus(siegeStatus);
        siege.setActualEndTime(System.currentTimeMillis());
        siege.setAttackerWinner(winnerNation);
        siege.setAllSiegeZonesToInactive();
        activateSiegeImmunityTimer(siege.getDefendingTown());

        //Save to db
        TownyUniverse.getDataSource().saveTown(siege.getDefendingTown());
        for(SiegeZone siegeZone: siege.getSiegeZones().values()) {
            TownyUniverse.getDataSource().saveSiegeZone(siegeZone);
        }
    }

    private static void activateSiegeImmunityTimer(Town town) {
        double siegeDuration = town.getSiege().getActualEndTime() - town.getSiege().getStartTime();
        double cooldownDuration = siegeDuration * TownySettings.getWarSiegeSiegeImmunityTimeModifier();
        town.setSiegeImmunityEndTime(System.currentTimeMillis() + (long)(cooldownDuration + 0.5));
    }

    public static void activateRevoltImmunityTimer(Town town) {
        long immunityDuration = (long)(TownySettings.getWarSiegeRevoltImmunityTimeHours() * ONE_HOUR_IN_MILLIS);
        town.setRevoltImmunityEndTime(System.currentTimeMillis() + immunityDuration);
    }

    public static TownyObject calculateSiegeWinner(Siege siege) {
        //If all siege zones are negative points, defender wins
        //Otherwise, the siege zone attacker with the highest points wins

        TownyObject winner = siege.getDefendingTown();
        int winningPoints = 0;

        for(Map.Entry<Nation,SiegeZone> entry: siege.getSiegeZones().entrySet()) {
            if(entry.getValue().isActive()
                && entry.getValue().getSiegePoints() > winningPoints) {
                winner = entry.getKey();
                winningPoints = entry.getValue().getSiegePoints();
            }
        }

        return winner;
    }

    public static String getFormattedTimeValue(double timeMillis) {
        String timeUnit;
        double timeUtilCompletion;

        if(timeMillis> 0) {

            NumberFormat numberFormat = NumberFormat.getInstance();

            if (timeMillis / ONE_DAY_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("day_plu");
                timeUtilCompletion = timeMillis / ONE_DAY_IN_MILLIS;

            } else if (timeMillis / ONE_HOUR_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("hour_plu");
                timeUtilCompletion = timeMillis / ONE_HOUR_IN_MILLIS;

            } else if (timeMillis / ONE_MINUTE_IN_MILLIS > 1) {
                numberFormat.setMaximumFractionDigits(1);
                timeUnit = TownySettings.getLangString("minute_plu");
                timeUtilCompletion = timeMillis / ONE_MINUTE_IN_MILLIS;

            } else {
                numberFormat.setMaximumFractionDigits(0);
                timeUnit = TownySettings.getLangString("second_plu");
                timeUtilCompletion = timeMillis / ONE_SECOND_IN_MILLIS;
            }

            double timeRoundedUp = Math.ceil(timeUtilCompletion * 10) / 10;
            return numberFormat.format(timeRoundedUp) + " " + timeUnit;

        } else {
            return "0";
        }
    }

    public static void processAttackTownRequest(Player player,
                                                   Block block,
                                                   List<TownBlock> nearbyTownBlocks,
                                                   BlockPlaceEvent event) {

        try {
            if (!TownySettings.getWarSiegeAttackEnabled())
                throw new TownyException("Siege Attacks not allowed");

            if (!TownyUniverse.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_NATION_SIEGE_ATTACK.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            if (nearbyTownBlocks.size() > 1)
                throw new TownyException("To start a siege attack, " +
                        "the wilderness plot containing the banner must be facing just one town plot. " +
                        "Try a different location for the banner");

            Town defendingTown = nearbyTownBlocks.get(0).getTown();
            Nation nationOfAttackingPlayer = TownyUniverse.getNationOfPlayer(player);

            if (defendingTown.hasNation()) {
                Nation nationOfDefendingTown = defendingTown.getNation();

                if(nationOfAttackingPlayer == nationOfDefendingTown)
                    throw new TownyException("You cannot attack a town in your own nation.");

                if (!nationOfAttackingPlayer.hasEnemy(nationOfDefendingTown))
                    throw new TownyException("You cannot attack a town unless the nation of that town is an enemy of your nation.");
            }

            if (nationOfAttackingPlayer.isNationAttackingTown(defendingTown))
                throw new TownyException("Your nation is already attacking this town.");

            if (defendingTown.isSiegeCooldownActive()) {
                throw new TownyException(
                        "This town is in a siege cooldown period. It cannot be attacked for " +
                                defendingTown.getFormattedHoursUntilSiegeImmunityEnds() + " hours");
            }

            if (TownySettings.isUsingEconomy()) {
                double initialSiegeCost =
                        TownySettings.getWarSiegeAttackerCostUpFrontPerPlot()
                                * defendingTown.getTownBlocks().size();

                if (nationOfAttackingPlayer.canPayFromHoldings(initialSiegeCost))
                    //Deduct upfront cost
                    nationOfAttackingPlayer.pay(initialSiegeCost, "Cost of Initiating an assault siege.");
                else {
                    throw new TownyException(TownySettings.getLangString("msg_err_no_money."));
                }
            }

            //THE FOLLOWING MATTER FOR POINTS BUT NOT FOR STARTINGSIEGE
            //if (player.isFlying())
            //    throw new TownyException("You cannot be flying to start a siege.");

            //if(player.getPotionEffect(PotionEffectType.INVISIBILITY) != null)
              //  throw new TownyException("The god(s) favour the brave. You cannot be invisible to start a siege");

            if (SiegeWarUtil.doesBlockHaveANonAirBlockAboveIt(block))
                throw new TownyException("The god(s) favour wars on the land surface. You must place the banner where there is only sky above it.");

            if (TownySettings.getNationRequiresProximity() > 0) {
                Coord capitalCoord = nationOfAttackingPlayer.getCapital().getHomeBlock().getCoord();
                Coord townCoord = defendingTown.getHomeBlock().getCoord();
                if (!nationOfAttackingPlayer.getCapital().getHomeBlock().getWorld().getName().equals(defendingTown.getHomeBlock().getWorld().getName())) {
                    throw new TownyException("This town cannot join your nation because the capital of your your nation is in a different world.");
                }
                double distance = Math.sqrt(Math.pow(capitalCoord.getX() - townCoord.getX(), 2) + Math.pow(capitalCoord.getZ() - townCoord.getZ(), 2));
                if (distance > TownySettings.getNationRequiresProximity()) {
                    throw new TownyException(String.format(TownySettings.getLangString("msg_err_town_not_close_enough_to_nation"), defendingTown.getName()));
                }
            }

            if (TownySettings.getMaxTownsPerNation() > 0) {
                if (nationOfAttackingPlayer.getTowns().size() >= TownySettings.getMaxTownsPerNation()){
                    throw new TownyException(String.format(TownySettings.getLangString("msg_err_nation_over_town_limit"), TownySettings.getMaxTownsPerNation()));
                }
            }

            //Setup attack
            attackTown(block, nationOfAttackingPlayer, defendingTown);
        } catch (TownyException x) {
            TownyMessaging.sendErrorMsg(player, x.getMessage());
            event.setCancelled(true);
        } catch (EconomyException x) {
            event.setCancelled(true);
            TownyMessaging.sendErrorMsg(player, x.getMessage());
        }
    }

    public static void processInvadeTownRequest(Towny plugin,
                                                   Player player,
                                                   String townName,
                                                   BlockPlaceEvent event) {
        try {
            if (!TownySettings.getWarSiegeInvadeEnabled())
                throw new TownyException("Invade not allowed. Try plunder instead.");

            if (!TownyUniverse.getDataSource().hasTown(townName))
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_not_registered_1"), townName));

            if (!TownyUniverse.getDataSource().getTown(townName).hasSiege())
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_siege_war_no_siege_on_target_town"), townName));

            if (!TownyUniverse.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_NATION_SIEGE_INVADE.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            final Siege siege = TownyUniverse.getDataSource().getTown(townName).getSiege();

            if (siege.getStatus() == SiegeStatus.IN_PROGRESS)
                throw new TownyException("A siege is still in progress. You cannot invade unless your nation is victorious in the siege");

            if (siege.getStatus() == SiegeStatus.DEFENDER_WIN)
                throw new TownyException("The defender has defeated all attackers. You cannot invade unless your nation is victorious in the siege");

            if (siege.getStatus() == SiegeStatus.ATTACKER_ABANDON)
                throw new TownyException("All attackers abandoned the siege. You cannot invade unless your nation is victorious in the siege");

            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());

            if(!resident.hasTown() || !resident.getTown().hasNation())
                throw new TownyException("You must be a resident of a town in a nation to use the invade action.");

            if (resident.getTown().getNation() != siege.getAttackerWinner())
                throw new TownyException("The town was defeated but not by your nation. You cannot invade unless your nation is victorious in the siege");

            if (siege.isTownInvaded())
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_siege_war_town_already_invaded"), townName));

            SiegeWarUtil.captureTown(plugin, siege, siege.getAttackerWinner(), siege.getDefendingTown());

        } catch (TownyException x) {
            event.setCancelled(true);
            TownyMessaging.sendErrorMsg(player, x.getMessage());
        }
    }

    public static void processPlunderTownRequest(Player player, String townName, BlockPlaceEvent event) {
        try {
            if (!TownySettings.getWarSiegeEnabled())
                throw new TownyException("Siege war feature disabled");

            if (!TownySettings.getWarSiegePlunderEnabled())
                throw new TownyException("Plunder not allowed. Try invade instead.");

            if (!TownyUniverse.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_NATION_SIEGE_PLUNDER.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            if(!TownyUniverse.getDataSource().hasTown(townName))
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_not_registered_1"), townName));

            if(!TownyUniverse.getDataSource().getTown(townName).hasSiege())
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_siege_war_no_siege_on_target_town"), townName));

            final Siege siege = TownyUniverse.getDataSource().getTown(townName).getSiege();

            if(siege.getStatus() == SiegeStatus.IN_PROGRESS)
                throw new TownyException("A siege is still in progress. You cannot plunder unless your nation is victorious in the siege");

            if(siege.getStatus() == SiegeStatus.DEFENDER_WIN)
                throw new TownyException("The defender has defeated all attackers. You cannot plunder unless your nation is victorious in the siege");

            if(siege.getStatus() == SiegeStatus.ATTACKER_ABANDON)
                throw new TownyException("All attackers abandoned the siege. You cannot plunder unless your nation is victorious in the siege");

            Resident resident = TownyUniverse.getDataSource().getResident(player.getName());

            if(!resident.hasTown()
                    || !resident.hasNation()
                    || resident.getTown().getNation() != siege.getAttackerWinner()) {
                throw new TownyException("The town was defeated but not by your nation. You cannot plunder unless your nation is victorious in the siege");
            }

            if(!TownySettings.isUsingEconomy())
                throw new TownyException("No economy plugin is active. Cannot plunder.");

            if(siege.isTownPlundered())
                throw new TownyException(String.format(TownySettings.getLangString("msg_err_siege_war_town_already_plundered"), townName));

            SiegeWarUtil.plunderTown(siege, siege.getDefendingTown(), siege.getAttackerWinner());
        } catch (TownyException x) {
            event.setCancelled(true);
            TownyMessaging.sendErrorMsg(player, x.getMessage());
        }
    }

    public static Siege getActiveSiegeGivenBannerLocation(Location location) {
        //Look through all siege zones
        //Note that we don't just look at the town at the given location
        //....because mayor may have unclaimed the plot after the siege started

        //Location must match
        //Siege must be in progress
        //Siege zone must be active
        for (SiegeZone siegeZone : TownyUniverse.getDataSource().getSiegeZones()) {
            if (siegeZone.getFlagLocation().equals(location)) {
                if (siegeZone.getSiege().getStatus() == SiegeStatus.IN_PROGRESS && siegeZone.isActive()) {
                    return siegeZone.getSiege();
                } else {
                    return null;
                }
            }

    }
        //No siege banner found at the given location
        return null;
    }

    public static List<Siege> getAllSieges() {
        List<Siege> result = new ArrayList<>();
        for(SiegeZone siegeZone: TownyUniverse.getDataSource().getSiegeZones()) {
            if(!result.contains(siegeZone.getSiege())) {
                result.add(siegeZone.getSiege());
            }
        }
        return result;
    }

    public static void evaluateSiegeZone(SiegeZone siegeZone) {
        System.out.println("Evaluating siege zone now");

        if(siegeZone.isActive()) {
            boolean siegeZoneChanged = false;
            Resident resident;

            //Cycle all online players
            for (Player player : BukkitTools.getOnlinePlayers()) {

                try {
                    resident = TownyUniverse.getDataSource().getResident(player.getName());

                    if (resident.hasTown()) {
                        //TODO - DEHARDCODE THE POINT VALUES

                        if (resident.getTown() == siegeZone.getDefendingTown()) {

                            //Resident of defending town
                            siegeZoneChanged =
                            siegeZoneChanged ||
                                    evaluateSiegeZoneOccupant(
                                    player,
                                    siegeZone,
                                    siegeZone.getDefenderPlayerScoreTimeMap(),
                                    TownySettings.getSiegeWarPointsPerDefendingPlayer());

                        }

                        if (resident.getTown().hasNation()) {

                            if (siegeZone.getDefendingTown().hasNation()
                                    && siegeZone.getDefendingTown().getNation()
                                    == resident.getTown().getNation()) {

                                //Nation member of defending town
                                siegeZoneChanged =
                                siegeZoneChanged ||
                                evaluateSiegeZoneOccupant(
                                        player,
                                        siegeZone,
                                        siegeZone.getDefenderPlayerScoreTimeMap(),
                                        TownySettings.getSiegeWarPointsPerDefendingPlayer());
                            }

                            if (siegeZone.getAttackingNation()
                                    == resident.getTown().getNation()) {

                                //Nation member of attacking nation
                                siegeZoneChanged =
                                siegeZoneChanged ||
                                evaluateSiegeZoneOccupant(
                                        player,
                                        siegeZone,
                                        siegeZone.getAttackerPlayerScoreTimeMap(),
                                        TownySettings.getSiegeWarPointsPerAttackingPlayer());
                            }
                        }
                    }
                } catch (NotRegisteredException e) {
                }
            }

            //Remove garbage from player score time maps
            siegeZoneChanged =
            siegeZoneChanged ||
            removeGarbageFromPlayerScoreTimeMap(siegeZone.getDefenderPlayerScoreTimeMap(),
                                                siegeZone.getDefendingTown(),
                                                null);

            siegeZoneChanged =
            siegeZoneChanged ||
            removeGarbageFromPlayerScoreTimeMap(siegeZone.getDefenderPlayerScoreTimeMap(),
                                                null,
                                                siegeZone.getAttackingNation());

            //Save siege zone to db if it was changed
            if(siegeZoneChanged) {
                TownyUniverse.getDataSource().saveSiegeZone(siegeZone);
            }
        }
    }

    private static boolean removeGarbageFromPlayerScoreTimeMap(Map<Player, Long> map,
                                                            Town townFilter,
                                                            Nation nationFilter) {

        boolean siegeZoneChanged = false;

        try {
            Iterator it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Player,Long> pair = (Map.Entry)it.next();

                if(!pair.getKey().isOnline()) {
                    it.remove();
                    siegeZoneChanged = true;
                    continue;
                }

                Resident resident = TownyUniverse.getDataSource().getResident(pair.getKey().getName());

                if(!resident.hasTown()) {
                    it.remove();
                    siegeZoneChanged = true;
                    continue;
                }

                if(townFilter != null && resident.getTown() != townFilter) {
                    it.remove();
                    siegeZoneChanged = true;
                    continue;
                }

                if(nationFilter != null && resident.getTown().getNation() != nationFilter) {
                    it.remove();
                    siegeZoneChanged = true;
                    continue;
                }
            }

        } catch (Exception e) {
        }

        return siegeZoneChanged;
    }

    private static boolean evaluateSiegeZoneOccupant(Player player,
                                                  SiegeZone siegeZone,
                                                  Map<Player, Long> playerScoreTimeMap,
                                                  int siegePointsForZoneOccupation) {

        //Is the player already registered as being in the siege zone ?
        if (playerScoreTimeMap.containsKey(player)) {

            //Player must still be in zone
            if (!isPlayerInSiegePointZone(player, siegeZone)) {
                playerScoreTimeMap.remove(player);
                return true;
            }

            //Player must still be in the open
            if(doesPlayerHaveANonAirBlockAboveThem(player)) {
                playerScoreTimeMap.remove(player);
                return true;
            }

            //Player must have been there long enough
            if (System.currentTimeMillis() > playerScoreTimeMap.get(player)) {
                siegeZone.adjustSiegePoints(siegePointsForZoneOccupation);
                playerScoreTimeMap.put(player,
                    System.currentTimeMillis()
                            + (TownySettings.getWarSiegeZoneOccupationScorintTimeRequirementSeconds() * ONE_SECOND_IN_MILLIS));
                return true;
            }

            return false; //No change

        } else {
            //Player must be in zone
            if (!isPlayerInSiegePointZone(player, siegeZone)) {
                return false;
            }

            //Player must be in the open
            if(doesPlayerHaveANonAirBlockAboveThem(player)) {
                return false;
            }

            //Player must not be flying or invisible
            if(player.isFlying() || player.getPotionEffect(PotionEffectType.INVISIBILITY) != null) {
                return false;
            }

            playerScoreTimeMap.put(player,
                    System.currentTimeMillis()
                            + (TownySettings.getWarSiegeZoneOccupationScorintTimeRequirementSeconds() * ONE_SECOND_IN_MILLIS));

            System.out.println("DURATION MILLIS: " + System.currentTimeMillis()
                    + (TownySettings.getWarSiegeZoneOccupationScorintTimeRequirementSeconds()
                    * ONE_SECOND_IN_MILLIS));

            return true; //Player added to zone
        }
    }

    //Must be in same world as flag
    //Must be within 1 townblock length of flag
    //Must be in wilderness
    private static boolean isPlayerInSiegePointZone(Player player, SiegeZone siegeZone) {

        if (player.getLocation().getWorld() == siegeZone.getFlagLocation().getWorld()
                && player.getLocation().distance(siegeZone.getFlagLocation()) < TownySettings.getTownBlockSize()) {

            TownBlock townBlock = TownyUniverse.getTownBlock(player.getLocation());
            if (townBlock == null) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }


    public static void evaluateSiege(Siege siege) {
        //Process active siege
        if (siege.getStatus() == SiegeStatus.IN_PROGRESS) {

            //If scheduled end time has arrived, choose winner
            if (System.currentTimeMillis() > siege.getScheduledEndTime()) {
                TownyObject siegeWinner = SiegeWarUtil.calculateSiegeWinner(siege);
                if (siegeWinner instanceof Town) {
                    SiegeWarUtil.defenderWin(siege, (Town) siegeWinner);
                } else {
                    SiegeWarUtil.attackerWin(siege, (Nation) siegeWinner);
                }

                //Save changes to db
                TownyUniverse.getDataSource().saveTown(siege.getDefendingTown());
            }

        } else {

            //Siege is finished.
            //Wait for siege immunity timer to end then delete siege
            if (System.currentTimeMillis() > siege.getDefendingTown().getSiegeImmunityEndTime()) {
                TownyUniverse.getDataSource().removeSiege(siege);
            }
        }

    }

    public static void ruinTown(Town town) {
        /*

- Create a ruin method....
  --- set recentlyRuined = true
  --- set recentlyRuinedEndTime
  --- set town balance to 0
  --- remove all residents from town
  --- set town nation to null
  --- set mayor to null
  --- set siege to null
  --- (LEAVE TOWN BLOCKS IN PLACE)
  --- set pvp to ON
  --- set all perms to OFF
  --- isOpen = false
  --- isPublic = false
  --- remove town from nation
         */

        town.setRecentlyRuined(true);
        town.setRecentlyRuinedEndTime(System.currentTimeMillis() + 60000); //TODo - dehardcode
        if(TownySettings.isUsingEconomy()) {
            try {
                town.setBalance(0,"Ruined");
            } catch (EconomyException e) {
            }
        }
        town.getResidents().clear();

    }
}