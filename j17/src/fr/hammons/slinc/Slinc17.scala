package fr.hammons.slinc

import jdk.incubator.foreign.CLinker
import fr.hammons.slinc.ScopeI.PlatformSpecific
import fr.hammons.slinc.modules.DescriptorModule
import fr.hammons.slinc.modules.given

class Slinc17(_jitManager: JitManager, linker: CLinker)(using val dm: DescriptorModule) extends Slinc:
  protected def jitManager = _jitManager
  protected def scopePlatformSpecific = Scope17(linker)
  protected def transitionsPlatformSpecific = Transitions17
  protected def libraryIPlatformSpecific = Library17(linker)

@SlincImpl(17)
object Slinc17:
  private val compiler =
    scala.quoted.staging.Compiler.make(getClass().getClassLoader().nn)
  private val linker = CLinker.getInstance().nn
  val default = Slinc17(JitManagerImpl(compiler), linker)
  val noJit = Slinc17(NoJitManager, linker)
  val immediateJit = Slinc17(InstantJitManager(compiler), linker)
