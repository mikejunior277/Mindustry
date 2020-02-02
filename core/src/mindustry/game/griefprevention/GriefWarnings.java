package mindustry.game.griefprevention;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.Array;
import arc.util.Log;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.entities.type.Player;
import mindustry.entities.type.Unit;
import mindustry.game.EventType.DepositEvent;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.gen.Call;
import mindustry.net.Packets.AdminAction;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.power.ItemLiquidGenerator;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.storage.Vault;

import static mindustry.Vars.*;
import static mindustry.Vars.player;

import java.time.Instant;
import java.util.WeakHashMap;

public class GriefWarnings {
    private Instant nextWarningTime = Instant.now();
    public WeakHashMap<Player, PlayerStats> playerStats = new WeakHashMap<>();
    /** whether or not to send warnings to all players */
    public boolean broadcast = true;
    /** whether or not to be very noisy about everything */
    public boolean verbose = false;
    /** whether or not to flat out state the obvious, pissing everyone off */
    public boolean debug = false;
    /** whether or not to show the persistent tileinfo display */
    public boolean tileInfoHud = false;
    /** whether or not to automatically ban when we are 100% sure that player is griefing (eg. intentionally crashing other clients) */
    public boolean autoban = false;

    public WeakHashMap<Tile, TileInfo> tileInfo = new WeakHashMap<>();

    public CommandHandler commandHandler = new CommandHandler();
    public FixGrief fixer = new FixGrief();
    public Auto auto;

    public GriefWarnings() {
        Events.on(DepositEvent.class, this::handleDeposit);
        Events.on(TileChangeEvent.class, this::handleTileChange);

        loadSettings();
    }

    public void loadSettings() {
        broadcast = Core.settings.getBool("griefwarnings.broadcast", false);
        verbose = Core.settings.getBool("griefwarnings.verbose", false);
        debug = Core.settings.getBool("griefwarnings.debug", false);
        tileInfoHud = Core.settings.getBool("griefwarnings.tileinfohud", false);
        autoban = Core.settings.getBool("griefwarnings.autoban", false);
    }

    public void saveSettings() {
        Core.settings.put("griefwarnings.broadcast", broadcast);
        Core.settings.put("griefwarnings.verbose", verbose);
        Core.settings.put("griefwarnings.debug", debug);
        Core.settings.put("griefwarnings.tileinfohud", tileInfoHud);
        Core.settings.put("griefwarnings.autoban", autoban);
        Core.settings.save();
    }

    public boolean sendMessage(String message, boolean throttled) {
        // if (!net.active()) return false;
        if (message.length() > maxTextLength) {
            ui.chatfrag.addMessage(
                    "[scarlet]WARNING: the following grief warning exceeded maximum allowed chat length and was not sent",
                    null);
            ui.chatfrag.addMessage(message, null);
            ui.chatfrag.addMessage("Message length was [accent]" + message.length(), null);
            return false;
        }
        if (!Instant.now().isAfter(nextWarningTime) && throttled) return false;
        nextWarningTime = Instant.now().plusSeconds(1);
        if (broadcast) Call.sendChatMessage(message);
        else if (net.client()) ui.chatfrag.addMessage(message, null);
        else if (net.server()) Log.info("[griefwarnings] " + message);
        return true;
    }

    public boolean sendMessage(String message) {
        return sendMessage(message, true);
    }

    public float getDistanceToCore(Unit unit, float x, float y) {
        Tile nearestCore = unit.getClosestCore().getTile();
        return Mathf.dst(x, y, nearestCore.x, nearestCore.y);
    }

    public float getDistanceToCore(Unit unit, Tile tile) {
        return getDistanceToCore(unit, tile.x, tile.y);
    }

    public float getDistanceToCore(Unit unit) {
        return getDistanceToCore(unit, unit.x, unit.y);
    }

    public void handleConnectFinish() {
        // TODO: future
    }

    public void handleDisconnect() {
        tileInfo.clear();
        playerStats.clear();
    }

    public void handleTileChange(TileChangeEvent event) {
        // if (event.tile.block() == Blocks.air) tileInfo.remove(event.tile);
    }

    public TileInfo getOrCreateTileInfo(Tile tile, boolean doLinking) {
        TileInfo info = tileInfo.get(tile);
        if (info == null) {
            TileInfo newInfo = new TileInfo();
            info = newInfo;
            tileInfo.put(tile, newInfo);
            if (doLinking) tile.getLinkedTiles(linked -> getOrCreateTileInfo(linked, false).doLink(newInfo));
        }
        return info;
    }

    public TileInfo getOrCreateTileInfo(Tile tile) {
        return getOrCreateTileInfo(tile, true);
    }

    public PlayerStats getOrCreatePlayerStats(Player player) {
        PlayerStats stats = playerStats.get(player);
        if (stats == null) {
            stats = new PlayerStats(player);
            playerStats.put(player, stats);
        }
        return stats;
    }

    public void handleBlockConstructProgress(Player builder, Tile tile, Block cblock, float progress, Block previous) {
        TileInfo info = getOrCreateTileInfo(tile);
        if (builder != null) info.constructedBy = builder;

        boolean didWarn = false;
        float coreDistance = getDistanceToCore(builder, tile);
        // persistent warnings that keep showing
        if (coreDistance < 30 && cblock instanceof NuclearReactor) {
            String message = "[scarlet]WARNING[] " + builder.name + "[white] ([stat]#" + builder.id
                    + "[]) is building a reactor [stat]" + Math.round(coreDistance) + "[] blocks from core. [stat]"
                    + Math.round(progress * 100) + "%";
            sendMessage(message);
            didWarn = true;
        } else if (coreDistance < 10 && cblock instanceof ItemLiquidGenerator) {
            String message = "[scarlet]WARNING[] " + builder.name + "[white] ([stat]#" + builder.id
                    + "[]) is building a generator [stat]" + Math.round(coreDistance) + "[] blocks from core. [stat]"
                    + Math.round(progress * 100) + "%";
            sendMessage(message);
            didWarn = true;
        }
        

        // one-time block construction warnings
        if (!info.constructSeen) {
            if (previous != null && previous != Blocks.air) info.previousBlock = previous;
            tile.getLinkedTiles(linked -> getOrCreateTileInfo(linked, false).doLink(info));
            info.constructSeen = true;
            info.currentBlock = cblock;

            if (!didWarn) {
                if (cblock instanceof NuclearReactor) {
                    Array<Tile> bordering = tile.entity.proximity;
                    boolean hasCryo = false;
                    for (Tile neighbor : bordering) {
                        if (
                            neighbor.entity != null && neighbor.entity.liquids != null &&
                            neighbor.entity.liquids.current() == Liquids.cryofluid
                        ) {
                            hasCryo = true;
                            break;
                        }
                    }
                    if (!hasCryo) {
                        String message = "[lightgray]Notice[] " + formatPlayer(builder) + 
                            " is building a reactor at " + formatTile(tile);
                        sendMessage(message, false);
                    }
                }
                /* doesn't seem very necessary for now
                if (cblock instanceof Fracker) {
                    String message = "[lightgray]Notice[] " + formatPlayer(builder) +
                        " is building an oil extractor at " + formatTile(tile);
                    sendMessage(message, false);
                }
                */
            }
        }
    }

    public void handleBlockConstructFinish(Tile tile, Block block, int builderId) {
        TileInfo info = getOrCreateTileInfo(tile);
        Player targetPlayer = playerGroup.getByID(builderId);
        tile.getLinkedTiles(linked -> getOrCreateTileInfo(linked, false).doLink(info));
        info.constructedBy = targetPlayer;
        info.currentBlock = block;

        if (debug && targetPlayer != null) {
            sendMessage("[cyan]Debug[] " + formatPlayer(targetPlayer) + " builds [accent]" +
                tile.block().name + "[] at " + formatTile(tile), false);
        }
    }

    public void handleBlockDeconstructProgress(Player builder, Tile tile, Block cblock, float progress, Block previous) {
        TileInfo info = getOrCreateTileInfo(tile);
        if (builder != null) info.deconstructedBy = builder;

        if (!info.deconstructSeen) {
            info.deconstructSeen = true;
        }
    }

    public void handleBlockDeconstructFinish(Tile tile, Block block, int builderId) {
        // this runs before the block is actually removed
        TileInfo info = getOrCreateTileInfo(tile);
        Player targetPlayer = playerGroup.getByID(builderId);
        if (targetPlayer != null) info.deconstructedBy = targetPlayer;
        info.reset();
        info.previousBlock = block;
        tile.getLinkedTiles(linked -> getOrCreateTileInfo(linked, false).unlink());

        if (debug && targetPlayer != null) {
            sendMessage("[cyan]Debug[] " + targetPlayer.name + "[white] ([stat]#" + builderId +
                "[]) deconstructs [accent]" + tile.block().name + "[] at " + formatTile(tile), false);
        }
    }

    public Player getNearestPlayerByLocation(float x, float y) {
        // grab every player in a 10x10 area and then find closest
        Array<Player> candidates = playerGroup.intersect(x - 5, y - 5, 10, 10);
        if (candidates.size == 0) return null;
        if (candidates.size == 1) return candidates.first();
        if (candidates.size > 1) {
            float nearestDistance = Float.MAX_VALUE;
            Player nearestPlayer = null;
            for (Player player : candidates) {
                float distance = Mathf.dst(x, y, player.x, player.y);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestPlayer = player;
                }
            }
            return nearestPlayer;
        }
        return null; // this should be impossible
    }

    public void handleDeposit(DepositEvent event) {
        Player targetPlayer = event.player;
        Tile tile = event.tile;
        Item item = event.item;
        int amount = event.amount;
        if (targetPlayer == null) return;
        if (verbose) {
            sendMessage("[green]Verbose[] " + targetPlayer.name + "[white] ([stat]#" + targetPlayer.id +
                "[]) transfers " + amount + " " + item.name + " to " + tile.block().name + " " + formatTile(tile), false);
        }
        if (item.equals(Items.thorium) && tile.block() instanceof NuclearReactor) {
            String message = "[scarlet]WARNING[] " + targetPlayer.name + "[white] ([stat]#" +
                targetPlayer.id + "[]) transfers [accent]" + amount + "[] thorium to a reactor. " + formatTile(tile);
            sendMessage(message);
            return;
        } else if (item.explosiveness > 0.5f) {
            Block block = tile.block();
            if (block instanceof ItemLiquidGenerator) {
                String message = "[scarlet]WARNING[] " + formatPlayer(targetPlayer) + " transfers [accent]" +
                    amount + "[] blast to a generator. " + formatTile(tile);
                sendMessage(message);
                return;
            } else if (block instanceof StorageBlock) {
                String message = "[scarlet]WARNING[] " + formatPlayer(targetPlayer) + " transfers [accent]" +
                    amount + "[] blast to a Container. " + formatTile(tile);
                sendMessage(message);
                return;
            } else if (block instanceof Vault) {
                String message = "[scarlet]WARNING[] " + formatPlayer(targetPlayer) + " transfers [accent]" +
                    amount + "[] blast to a Vault. " + formatTile(tile);
                sendMessage(message);
                return;
            }
        }
    }

    public void handlePlayerEntitySnapshot(Player targetPlayer) {
        // System.out.println("received entity snapshot for " + targetPlayer.name + "#" + targetPlayer.id);
        // System.out.println("entity previous: " + playerStats.get(targetPlayer));
        PlayerStats stats = getOrCreatePlayerStats(targetPlayer);
        if (debug) {
            sendMessage("[cyan]Debug[] Player snapshot: " + targetPlayer.name + "[white] ([stat]#" + targetPlayer.id + "[])", false);
        }
    }

    public void handlePlayerDisconnect(int playerId) {
        Player targetPlayer = playerGroup.getByID(playerId);
        // System.out.println("player disconnect: " + targetPlayer.name + "#" + targetPlayer.id);
        playerStats.remove(targetPlayer);
        if (debug) {
            sendMessage("[cyan]Debug[] Player disconnect: " + targetPlayer.name + "[white] ([stat]#" + targetPlayer.id + "[])", false);
        }
    }

    public void handleWorldDataBegin() {
        playerStats.clear();
    }

    public String formatPlayer(Player targetPlayer) {
        String playerString;
        if (targetPlayer != null) {
            playerString = targetPlayer.name + "[white] ([stat]#" + targetPlayer.id + "[])";
        } else {
            playerString = "[lightgray]unknown[]";
        }
        return playerString;
    }

    public String formatColor(Color color) {
        return "[#" + Integer.toHexString(((int)(255 * color.r) << 24) | ((int)(255 * color.g) << 16) | ((int)(255 * color.b) << 8)) + "]";
    }

    public String formatColor(Color color, String toFormat) {
        return formatColor(color) + toFormat + "[]";
    }

    public String formatItem(Item item) {
        if (item == null) return "(none)";
        return formatColor(item.color, item.name);
    }

    public String formatTile(Tile tile) {
        if (tile == null) return "(none)";
        return "(" + tile.x + ", " + tile.y + ")";
    }

    public String formatRatelimit(Ratelimit rl) {
        return (rl.check() ? "exceeded" : "not exceeded") + " (" + rl.events() + " events in " + rl.findTime + " ms)";
    }

    public String formatRatelimit(Ratelimit rl, Player source) {
        return (rl.check() ? "exceeded" : "not exceeded") + " for player " + formatPlayer(source) + " (" + rl.events() + " events in " + rl.findTime + " ms)";
    }

    public void handlePowerGraphSplit(Player targetPlayer, Tile tile, PowerGraph oldGraph, PowerGraph newGraph1, PowerGraph newGraph2) {
        int oldGraphCount = oldGraph.all.size;
        int newGraph1Count = newGraph1.all.size;
        int newGraph2Count = newGraph2.all.size;

        if (Math.min(oldGraphCount - newGraph1Count, oldGraphCount - newGraph2Count) > 100) {
            sendMessage("[lightgray]Notice[] Power split by " + formatPlayer(targetPlayer) + " " + oldGraphCount + " -> " +
                newGraph1Count + "/" + newGraph2Count + " " + formatTile(tile));
        }
    }

    public void handleBlockBeforeConfigure(Tile tile, Player targetPlayer, int value) {
        TileInfo info = getOrCreateTileInfo(tile);
        if (targetPlayer != null) {
            info.logInteraction(targetPlayer);

            PlayerStats stats = getOrCreatePlayerStats(targetPlayer);
            if (stats.configureRatelimit.get()) {
                stats.configureRatelimit.nextTick(rl -> sendMessage("[scarlet]WARNING[] Configure ratelimit " + formatRatelimit(rl, targetPlayer)));
            }
        }

        Block block = tile.block();
        if (block instanceof Sorter) {
            Item oldItem = tile.<Sorter.SorterEntity>ent().sortItem;
            Item newItem = content.item(value);
            if (verbose) {
                sendMessage("[green]Verbose[] " + formatPlayer(targetPlayer) + " configures sorter " +
                    formatItem(oldItem) + " -> " + formatItem(newItem) + " " + formatTile(tile));
            }
        } else if (block instanceof MassDriver) {
            Tile oldLink = world.tile(tile.<MassDriver.MassDriverEntity>ent().link);
            Tile newLink = world.tile(value);
            if (verbose) {
                sendMessage("[green]Verbose[] " + formatPlayer(targetPlayer) + " configures mass driver at " +
                    formatTile(tile) + " from " + formatTile(oldLink) + " to " + formatTile(newLink));
            }
        }
    }
    
    public void handleRotateBlock(Player targetPlayer, Tile tile, boolean direction) {
        TileInfo info = getOrCreateTileInfo(tile);
        if (targetPlayer != null) {
            info.lastRotatedBy = targetPlayer;
            info.logInteraction(targetPlayer);
        }

        if (verbose) {
            sendMessage("[green]Verbose[] " + formatPlayer(targetPlayer) + " rotates " +
                tile.block().name + " at " + formatTile(tile));
        }

        PlayerStats stats = getOrCreatePlayerStats(targetPlayer);
        if (stats.rotateRatelimit.get()) {
            stats.rotateRatelimit.nextTick(rl -> sendMessage("[scarlet]WARNING[] Rotate ratelimit " + formatRatelimit(rl, targetPlayer)));
        }
    }

    public void handleThoriumReactorHeat(Tile tile, float heat) {
        if (heat > 0.15f && tile.interactable(player.getTeam())) {
            sendMessage("[scarlet]WARNING[] Thorium reactor at " + formatTile(tile) + " is overheating! Heat: [accent]" + heat);
        }
    }

    public boolean doAutoban(Player targetPlayer, String reason) {
        if (player.isAdmin && targetPlayer != null && autoban) {
            Call.onAdminRequest(targetPlayer, AdminAction.ban);
            String message = "[yellow]Autoban[] Banning player " + formatPlayer(targetPlayer);
            if (reason != null) message += " (" + reason + ")";
            sendMessage(message, false);
            return true;
        } else return false;
    }

    public TileInfo getTileInfo(Tile tile) {
        TileInfo info = tileInfo.get(tile);
        if (info != null && info.link != null) info = info.link;
        return info;
    }

    public void handleMessageBlockText(Player targetPlayer, Tile tile, String text) {
        // TODO: maybe log what the text said?
        if (targetPlayer == null) return;
        TileInfo info = getOrCreateTileInfo(tile);
        info.logInteraction(targetPlayer);
    }

    public void loadComplete() {
        auto = new Auto();
    }
}