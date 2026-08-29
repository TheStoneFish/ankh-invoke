package org.inksnow.ankhinvoke.bukkit.asm;

import org.inksnow.ankhinvoke.bukkit.util.CraftBukkitVersion;
import org.inksnow.ankhinvoke.comments.InternalName;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.commons.Remapper;

public class NmsVersionRemapper extends Remapper {
  private final boolean remapCraftBukkitVersion;

  public NmsVersionRemapper() {
    this(true);
  }

  public NmsVersionRemapper(boolean remapCraftBukkitVersion) {
    this.remapCraftBukkitVersion = remapCraftBukkitVersion;
  }

  @Override
  public @InternalName @NotNull String map(@InternalName @NotNull String name) {
    String matchedPrefix;
    boolean craftBukkit = false;
    if (name.startsWith("org/bukkit/craftbukkit/")) {
      matchedPrefix = "org/bukkit/craftbukkit/";
      craftBukkit = true;
    } else if (!CraftBukkitVersion.v1_17_R1.isSupport() && name.startsWith("net/minecraft/server/")) {
      matchedPrefix = "net/minecraft/server/";
    } else {
      return name;
    }
    String remainder = name.substring(matchedPrefix.length());
    int splitIndex = remainder.indexOf('/');
    if (craftBukkit && (splitIndex < 0 || !isVersionSegment(remainder.substring(0, splitIndex)))) {
      return remapCraftBukkitVersion
          ? matchedPrefix + CraftBukkitVersion.current() + "/" + remainder
          : name;
    }
    if (splitIndex < 0) {
      return name;
    }
    if (craftBukkit && !remapCraftBukkitVersion) {
      return matchedPrefix + remainder.substring(splitIndex + 1);
    }
    return matchedPrefix + CraftBukkitVersion.current() + remainder.substring(splitIndex);
  }

  private static boolean isVersionSegment(String segment) {
    return segment.matches("v\\d+_\\d+_R\\d+");
  }
}
