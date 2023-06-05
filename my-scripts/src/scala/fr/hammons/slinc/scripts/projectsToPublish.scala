package fr.hammons.slinc.scripts

import bleep.model

object projectsToPublish {
  // will publish these with dependencies
  def include(crossName: model.CrossProjectName): Boolean =
    crossName.name.value match {
      case "core"                             => true
      case name if name.startsWith("bleep-plugin") => true
      case _                                       => false
    }
}