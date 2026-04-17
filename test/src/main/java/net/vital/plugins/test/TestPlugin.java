package net.vital.plugins.test;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import vital.api.ui.Combat;
import vital.api.world.Game;

@Slf4j
@PluginDescriptor(
	name = "Test",
	description = "Scratch plugin for testing VitalAPI methods",
	tags = {"test"}
)
public class TestPlugin extends Plugin
{
	private static final int STYLE_ACCURATE   = 1;
	private static final int STYLE_AGGRESSIVE = 2;

	private boolean switched;

	@Override
	protected void startUp()
	{
		switched = false;
		log.info("Test plugin started");
	}

	@Override
	protected void shutDown()
	{
		log.info("Test plugin stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (switched || !Game.isLoggedIn())
		{
			return;
		}

		int currentVarp = Combat.getCombatStyle();
		log.info("Current combat style varp={} (1-based={})", currentVarp, currentVarp + 1);

		if (currentVarp + 1 != STYLE_ACCURATE)
		{
			log.info("Not on Accurate, skipping switch test");
			switched = true;
			return;
		}

		boolean ok = Combat.selectCombatStyle(STYLE_AGGRESSIVE);
		log.info("selectCombatStyle(Aggressive) returned {}, new varp={}", ok, Combat.getCombatStyle());
		switched = true;
	}
}
