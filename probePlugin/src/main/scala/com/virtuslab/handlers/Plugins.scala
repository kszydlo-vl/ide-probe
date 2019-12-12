package com.virtuslab.handlers

import com.intellij.ide.plugins.PluginManagerCore
import com.virtuslab.ideprobe.protocol.InstalledPlugin
import scala.jdk.CollectionConverters._

object Plugins {
  def list: Seq[InstalledPlugin] = {
    PluginManagerCore.getLoadedPlugins.asScala
      .map(plugin => InstalledPlugin(plugin.getPluginId.getIdString, plugin.getVersion))
      .toSeq
  }
}
