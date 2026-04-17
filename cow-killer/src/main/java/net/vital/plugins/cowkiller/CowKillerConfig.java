package net.vital.plugins.cowkiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("cowkiller")
public interface CowKillerConfig extends Config
{
	@ConfigItem(
		keyName = "minStopLevel",
		name = "Min Stop Level",
		description = "Lower bound for per-stat random stop level (Att/Str/Def)"
	)
	default int minStopLevel()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "maxStopLevel",
		name = "Max Stop Level",
		description = "Upper bound for per-stat random stop level (Att/Str/Def)"
	)
	default int maxStopLevel()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "fleeHp",
		name = "Flee HP",
		description = "Emergency flee when current HP is at or below this"
	)
	default int fleeHp()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "buryBones",
		name = "Bury Bones",
		description = "Bury bones from kills (gives Prayer XP); otherwise drop when inventory full"
	)
	default boolean buryBones()
	{
		return true;
	}
}
