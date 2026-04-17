package net.vital.plugins.cowkiller;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import vital.api.containers.Inventory;
import vital.api.containers.InventoryItem;
import vital.api.entities.Npc;
import vital.api.entities.NpcList;
import vital.api.entities.Player;
import vital.api.entities.Players;
import vital.api.entities.TileItem;
import vital.api.entities.TileItems;
import vital.api.entities.Tiles;
import vital.api.input.Movement;
import vital.api.ui.Combat;
import vital.api.world.Game;
import vital.api.world.Pathfinder;

import java.util.List;
import java.util.Random;

/**
 * Kills cows in the Lumbridge cow pen with random style rotation.
 * Port of the combat-training portion of DreambotScripts/f2p-builder (MainScript.kt).
 *
 * Scope notes:
 *  - Starts wherever the player is; pathfinds to the cow pen.
 *  - Rotates combat style every 10-20 minutes, stops each stat at a random 20-25.
 *  - Emergency flees to a safe tile when HP drops too low, waits for regen.
 *  - Inventory full: buries bones (optional) and drops cowhides. No banking / gearup.
 *  - No quest progression (Romeo & Juliet is out of scope for this port).
 */
@Slf4j
@PluginDescriptor(
	name = "Cow Killer",
	description = "Kills cows in Lumbridge pen with style rotation (combat trainer)",
	tags = {"combat", "cow", "melee", "f2p"}
)
public class CowKillerPlugin extends Plugin
{
	// ── Location constants (matches f2p-builder MainScript) ──
	private static final int COW_PEN_MIN_X = 3242, COW_PEN_MAX_X = 3265;
	private static final int COW_PEN_MIN_Y = 3255, COW_PEN_MAX_Y = 3300;
	private static final int SAFE_TILE_X = 3253, SAFE_TILE_Y = 3235;

	// ── Skill indices (see Game.getBaseLevel javadoc) ──
	private static final int SKILL_ATTACK = 0;
	private static final int SKILL_DEFENCE = 1;
	private static final int SKILL_STRENGTH = 2;

	// ── Combat style indices for selectCombatStyle (typical melee weapon) ──
	// 0 = Accurate (Attack xp), 1 = Aggressive (Strength), 2 = Defensive (Defence)
	private static final int STYLE_ATTACK = 0;
	private static final int STYLE_STRENGTH = 1;
	private static final int STYLE_DEFENCE = 2;

	// ── Pathing ──
	private static final int STRIDE = 4;                  // x,y,plane,transport per tile in pathfinder output
	private static final int STEP_TILES_MIN = 6;
	private static final int STEP_TILES_MAX = 12;
	private static final int DEVIATION_THRESHOLD = 12;
	private static final int ARRIVAL_THRESHOLD = 2;
	private static final int STUCK_TICKS = 10;

	// ── Style rotation (game ticks; 1 tick ≈ 0.6s) ──
	private static final int STYLE_SWITCH_MIN_TICKS = 1000; // ~10 min
	private static final int STYLE_SWITCH_MAX_TICKS = 2000; // ~20 min

	private enum State { WALK_TO_COWS, SET_STYLE, FIGHTING, FLEEING, HEALING, DONE }

	@Inject private CowKillerConfig config;

	private final Random rng = new Random();

	private State state = State.WALK_TO_COWS;
	private int cooldownTicks;

	// Per-stat random stop levels (rolled at startup)
	private int stopLevelAtk, stopLevelStr, stopLevelDef;

	// Style rotation timer
	private int lastStyleSwitchTick;
	private int nextSwitchInterval;

	// Walking state
	private int[] path;
	private int pathIndex;
	private int pathTargetX, pathTargetY;
	private int lastPlayerX = -1, lastPlayerY = -1;
	private int stuckTicks;

	@Provides
	CowKillerConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(CowKillerConfig.class);
	}

	@Override
	protected void startUp()
	{
		int min = config.minStopLevel();
		int max = Math.max(min, config.maxStopLevel());
		stopLevelAtk = rollStop(min, max);
		stopLevelStr = rollStop(min, max);
		stopLevelDef = rollStop(min, max);
		rollStyleInterval();
		lastStyleSwitchTick = 0;
		state = State.WALK_TO_COWS;
		resetPath();
		cooldownTicks = 0;
		log.info("Cow Killer started — stops: atk={} str={} def={}", stopLevelAtk, stopLevelStr, stopLevelDef);
	}

	@Override
	protected void shutDown()
	{
		resetPath();
		log.info("Cow Killer stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!Game.isLoggedIn())
		{
			return;
		}

		if (cooldownTicks > 0)
		{
			cooldownTicks--;
			return;
		}

		Player local = Players.getLocal();
		if (local == null)
		{
			return;
		}
		int localIdx = Players.getLocalPlayerIndex();
		if (localIdx < 0)
		{
			return;
		}

		// playerGetTileX/Y return SCENE coords; convert to real world coords for comparisons.
		int[] worldPos = Tiles.sceneToWorld(local.getTileX(), local.getTileY());
		if (worldPos == null)
		{
			return;
		}
		int wx = worldPos[0], wy = worldPos[1];

		// Emergency flee — highest priority
		if (state != State.FLEEING && state != State.HEALING && Game.getHP() <= config.fleeHp())
		{
			log.info("HP low ({}), fleeing!", Game.getHP());
			state = State.FLEEING;
			resetPath();
		}

		switch (state)
		{
			case WALK_TO_COWS:
				walkToCows(localIdx, wx, wy);
				break;
			case SET_STYLE:
				setStyle();
				break;
			case FIGHTING:
				fight(localIdx, wx, wy);
				break;
			case FLEEING:
				flee(localIdx, wx, wy);
				break;
			case HEALING:
				heal();
				break;
			case DONE:
				break;
		}
	}

	// ── State handlers ──────────────────────────────────────────────────

	private void walkToCows(int localIdx, int wx, int wy)
	{
		if (inCowPen(wx, wy))
		{
			log.info("Arrived at cow pen");
			resetPath();
			state = State.SET_STYLE;
			return;
		}

		if (pathTargetX == 0 && pathTargetY == 0)
		{
			pathTargetX = COW_PEN_MIN_X + rng.nextInt(COW_PEN_MAX_X - COW_PEN_MIN_X + 1);
			pathTargetY = COW_PEN_MIN_Y + rng.nextInt(COW_PEN_MAX_Y - COW_PEN_MIN_Y + 1);
		}
		stepAlongPath(localIdx, wx, wy, pathTargetX, pathTargetY);
	}

	private void setStyle()
	{
		if (allGoalsMet())
		{
			log.info("Combat goals met — Att={} Str={} Def={}",
				Game.getBaseLevel(SKILL_ATTACK),
				Game.getBaseLevel(SKILL_STRENGTH),
				Game.getBaseLevel(SKILL_DEFENCE));
			state = State.DONE;
			return;
		}
		int style = pickTrainableStyle();
		if (style >= 0 && Combat.getCombatStyle() != style)
		{
			Combat.selectCombatStyle(style);
			log.info("Set combat style to {}", styleName(style));
			cooldownTicks = 1;
		}
		lastStyleSwitchTick = Game.getTickCount();
		rollStyleInterval();
		state = State.FIGHTING;
	}

	private void fight(int localIdx, int wx, int wy)
	{
		// Goals met
		if (allGoalsMet())
		{
			state = State.DONE;
			return;
		}

		// Rotation timer expired
		if (Game.getTickCount() - lastStyleSwitchTick > nextSwitchInterval)
		{
			log.info("Style rotation timer expired, switching");
			state = State.SET_STYLE;
			return;
		}

		// Current style's stat is capped — switch
		if (currentStyleCapped())
		{
			log.info("Current style capped, switching");
			state = State.SET_STYLE;
			return;
		}

		// Inventory full — clear space
		if (Inventory.getFreeSlots() == 0)
		{
			if (clearInventoryItem())
			{
				return;
			}
		}

		// Not in pen — walk back
		if (!inCowPen(wx, wy))
		{
			state = State.WALK_TO_COWS;
			resetPath();
			return;
		}

		// Randomly bury bones for Prayer XP
		if (config.buryBones() && Inventory.contains("Bones") && rng.nextInt(3) == 0)
		{
			InventoryItem bones = Inventory.getFirst("Bones");
			if (bones != null)
			{
				bones.interact("Bury");
				cooldownTicks = 1;
				return;
			}
		}

		// Busy — wait
		if (Players.isMoving(localIdx) || Players.isAnimating(localIdx) || Players.isInteracting(localIdx))
		{
			return;
		}

		// Take loot
		TileItem hide = nearestLoot("Cowhide", wx, wy);
		if (hide != null)
		{
			hide.interact("Take");
			cooldownTicks = 2;
			return;
		}
		TileItem bones = nearestLoot("Bones", wx, wy);
		if (bones != null)
		{
			bones.interact("Take");
			cooldownTicks = 2;
			return;
		}

		// Attack a cow
		Npc cow = nearestCow(wx, wy, localIdx);
		if (cow != null)
		{
			cow.interact("Attack");
			cooldownTicks = 2;
		}
	}

	private void flee(int localIdx, int wx, int wy)
	{
		int dx = wx - SAFE_TILE_X;
		int dy = wy - SAFE_TILE_Y;
		if (dx * dx + dy * dy < 25)
		{
			log.info("Reached safe tile, healing");
			resetPath();
			state = State.HEALING;
			return;
		}
		stepAlongPath(localIdx, wx, wy, SAFE_TILE_X, SAFE_TILE_Y);
	}

	private void heal()
	{
		int hp = Game.getHP();
		int max = Game.getMaxHP();
		if (hp >= max - 1)
		{
			log.info("HP restored ({}/{}), returning to cows", hp, max);
			state = State.WALK_TO_COWS;
			resetPath();
			return;
		}

		// Bury bones while we wait
		if (config.buryBones() && Inventory.contains("Bones"))
		{
			InventoryItem b = Inventory.getFirst("Bones");
			if (b != null)
			{
				b.interact("Bury");
				cooldownTicks = 1;
			}
		}
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private boolean inCowPen(int wx, int wy)
	{
		return wx >= COW_PEN_MIN_X && wx <= COW_PEN_MAX_X
			&& wy >= COW_PEN_MIN_Y && wy <= COW_PEN_MAX_Y;
	}

	private boolean allGoalsMet()
	{
		int min = config.minStopLevel();
		return Game.getBaseLevel(SKILL_ATTACK) >= min
			&& Game.getBaseLevel(SKILL_STRENGTH) >= min
			&& Game.getBaseLevel(SKILL_DEFENCE) >= min;
	}

	private boolean currentStyleCapped()
	{
		switch (Combat.getCombatStyle())
		{
			case STYLE_ATTACK:   return Game.getBaseLevel(SKILL_ATTACK)   >= stopLevelAtk;
			case STYLE_STRENGTH: return Game.getBaseLevel(SKILL_STRENGTH) >= stopLevelStr;
			case STYLE_DEFENCE:  return Game.getBaseLevel(SKILL_DEFENCE)  >= stopLevelDef;
			default: return false;
		}
	}

	private int pickTrainableStyle()
	{
		int[] candidates = new int[3];
		int n = 0;
		if (Game.getBaseLevel(SKILL_ATTACK)   < stopLevelAtk) candidates[n++] = STYLE_ATTACK;
		if (Game.getBaseLevel(SKILL_STRENGTH) < stopLevelStr) candidates[n++] = STYLE_STRENGTH;
		if (Game.getBaseLevel(SKILL_DEFENCE)  < stopLevelDef) candidates[n++] = STYLE_DEFENCE;
		return n == 0 ? -1 : candidates[rng.nextInt(n)];
	}

	private String styleName(int s)
	{
		switch (s)
		{
			case STYLE_ATTACK:   return "Accurate (Attack)";
			case STYLE_STRENGTH: return "Aggressive (Strength)";
			case STYLE_DEFENCE:  return "Defensive (Defence)";
			default:             return "style " + s;
		}
	}

	private void rollStyleInterval()
	{
		nextSwitchInterval = STYLE_SWITCH_MIN_TICKS
			+ rng.nextInt(STYLE_SWITCH_MAX_TICKS - STYLE_SWITCH_MIN_TICKS + 1);
	}

	private int rollStop(int min, int max)
	{
		return max <= min ? min : min + rng.nextInt(max - min + 1);
	}

	/** Remove one item from inventory to free a slot. Returns true if an action was taken. */
	private boolean clearInventoryItem()
	{
		if (config.buryBones() && Inventory.contains("Bones"))
		{
			InventoryItem bones = Inventory.getFirst("Bones");
			if (bones != null)
			{
				bones.interact("Bury");
				cooldownTicks = 1;
				return true;
			}
		}
		InventoryItem hide = Inventory.getFirst("Cowhide");
		if (hide != null)
		{
			hide.interact("Drop");
			cooldownTicks = 1;
			return true;
		}
		InventoryItem bones = Inventory.getFirst("Bones");
		if (bones != null)
		{
			bones.interact("Drop");
			cooldownTicks = 1;
			return true;
		}
		return false;
	}

	private TileItem nearestLoot(String name, int wx, int wy)
	{
		List<TileItem> all = TileItems.getAll();
		TileItem best = null;
		int bestDist = Integer.MAX_VALUE;
		for (TileItem it : all)
		{
			String iname = Inventory.getItemName(it.typeId);
			if (iname == null || !iname.equalsIgnoreCase(name))
			{
				continue;
			}
			// TileItem.tileX/Y are scene coords — convert to world for bounds check.
			int[] iw = Tiles.sceneToWorld(it.tileX, it.tileY);
			if (iw == null || !inCowPen(iw[0], iw[1]))
			{
				continue;
			}
			int dx = iw[0] - wx;
			int dy = iw[1] - wy;
			int d = dx * dx + dy * dy;
			if (d < bestDist)
			{
				bestDist = d;
				best = it;
			}
		}
		return best;
	}

	private Npc nearestCow(int wx, int wy, int localIdx)
	{
		List<Npc> npcs = NpcList.getAll();
		Npc best = null;
		int bestDist = Integer.MAX_VALUE;
		int myInteracting = Players.isInteracting(localIdx) ? Players.getInteractingIndex(localIdx) : -1;

		for (Npc n : npcs)
		{
			String name = n.getName();
			if (name == null || !name.equalsIgnoreCase("Cow"))
			{
				continue;
			}
			if (n.getCombatLevel() <= 0)
			{
				continue;
			}
			// npcGetTileX/Y are scene coords — convert to world for bounds check.
			int[] nw = Tiles.sceneToWorld(n.getTileX(), n.getTileY());
			if (nw == null || !inCowPen(nw[0], nw[1]))
			{
				continue;
			}
			// Skip cows someone else (or us) is already fighting
			if (n.getServerIndex() == myInteracting)
			{
				continue;
			}
			if (Bridge_npcInteracting(n))
			{
				continue;
			}
			int dx = nw[0] - wx;
			int dy = nw[1] - wy;
			int d = dx * dx + dy * dy;
			if (d < bestDist)
			{
				bestDist = d;
				best = n;
			}
		}
		return best;
	}

	/** Wrapper so tests/compile can find the API call even if name changes. */
	private boolean Bridge_npcInteracting(Npc n)
	{
		return vital.api.Bridge.npcIsInteracting(n.getServerIndex());
	}

	// ── Walking ─────────────────────────────────────────────────────────

	private void resetPath()
	{
		path = null;
		pathIndex = 0;
		pathTargetX = 0;
		pathTargetY = 0;
		lastPlayerX = -1;
		lastPlayerY = -1;
		stuckTicks = 0;
	}

	/**
	 * Pathfind and walk one step toward (destX,destY) on plane 0.
	 * Minimal port of the walker plugin's stepping logic for single-plane routes.
	 */
	private void stepAlongPath(int localIdx, int wx, int wy, int destX, int destY)
	{
		int dx = Math.abs(wx - destX);
		int dy = Math.abs(wy - destY);
		if (dx <= ARRIVAL_THRESHOLD && dy <= ARRIVAL_THRESHOLD)
		{
			return;
		}

		if (path == null || pathTargetX != destX || pathTargetY != destY)
		{
			path = Pathfinder.findPath(wx, wy, destX, destY, 0);
			pathIndex = 0;
			pathTargetX = destX;
			pathTargetY = destY;
			lastPlayerX = -1;
			lastPlayerY = -1;
			stuckTicks = 0;
			if (path == null)
			{
				log.warn("No path from ({},{}) to ({},{})", wx, wy, destX, destY);
				cooldownTicks = 3;
				return;
			}
		}

		if (Players.isMoving(localIdx))
		{
			lastPlayerX = wx;
			lastPlayerY = wy;
			stuckTicks = 0;
			return;
		}

		// Stuck detection
		if (wx == lastPlayerX && wy == lastPlayerY)
		{
			stuckTicks++;
			if (stuckTicks > STUCK_TICKS)
			{
				log.warn("Stuck at ({},{}) — re-pathing", wx, wy);
				path = null;
				stuckTicks = 0;
				return;
			}
		}
		else
		{
			stuckTicks = 0;
		}

		// Advance pathIndex: nearest point on path
		int bestDist = Integer.MAX_VALUE;
		int bestIdx = pathIndex;
		int scanLimit = Math.min(path.length - (STRIDE - 1), pathIndex + 20 * STRIDE);
		for (int i = pathIndex; i < scanLimit; i += STRIDE)
		{
			int pdx = wx - path[i];
			int pdy = wy - path[i + 1];
			int d = pdx * pdx + pdy * pdy;
			if (d < bestDist)
			{
				bestDist = d;
				bestIdx = i;
			}
		}
		pathIndex = bestIdx;

		// Deviation check
		double deviation = Math.sqrt(bestDist);
		if (deviation > DEVIATION_THRESHOLD)
		{
			log.info("Deviated {} tiles from path, re-pathfinding", (int) deviation);
			path = null;
			return;
		}

		// Pick a tile N steps ahead and walk there
		int stepsAhead = STEP_TILES_MIN + rng.nextInt(STEP_TILES_MAX - STEP_TILES_MIN + 1);
		int targetIdx = Math.min(pathIndex + stepsAhead * STRIDE, path.length - STRIDE);
		int tWorldX = path[targetIdx];
		int tWorldY = path[targetIdx + 1];

		int[] sc = Tiles.worldToScene(tWorldX, tWorldY);
		if (sc == null)
		{
			log.warn("worldToScene failed for ({},{})", tWorldX, tWorldY);
			return;
		}
		Movement.walkTo(sc[0], sc[1]);
		lastPlayerX = wx;
		lastPlayerY = wy;
	}
}
