package net.vital.plugins.test;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Test",
	description = "Scratch plugin for testing VitalAPI methods",
	tags = {"test"}
)
public class TestPlugin extends Plugin
{
	@Override
	protected void startUp()
	{
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
	}
}
