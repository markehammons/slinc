package fr.hammons.slinc.modules

import fr.hammons.slinc.*
import java.util.concurrent.atomic.AtomicReference

import fr.hammons.slinc.LibBacking

import fr.hammons.slinc.CFunctionRuntimeInformation
given libModule19: LibModule with
  override val runtimeVersion: Int = 19

  override def getLibrary(
      desc: List[CFunctionDescriptor],
      generators: List[CFunctionBindingGenerator]
  ): LibBacking[?] =
    import LinkageModule19.*
    val fns = desc
      .zip(generators)
      .map:
        case (cfd, generator) =>
          val runtimeInformation = CFunctionRuntimeInformation(cfd)
          val addr = defaultLookup(runtimeInformation.name).get
          val mh: MethodHandler = MethodHandler((v: Seq[Variadic]) =>
            getDowncall(cfd, v).bindTo(addr).nn
          )

          val fn =
            generator.generate(
              mh,
              runtimeInformation,
              (allocator, varArgs) =>
                varArgs.map: varArg =>
                  varArg.use[DescriptorOf]: descriptorOf ?=>
                    data =>
                      transitionModule19.methodArgument(
                        descriptorOf.descriptor,
                        data,
                        allocator
                      ),
              tempScope()
            )

          AtomicReference(fn)
    LibBacking(IArray.from(fns)).asInstanceOf[LibBacking[?]]
