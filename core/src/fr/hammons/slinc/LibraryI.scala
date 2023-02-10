package fr.hammons.slinc

import scala.quoted.*
import java.lang.invoke.MethodHandle
import fr.hammons.slinc.modules.TransitionModule
import scala.compiletime.asMatchable
import scala.annotation.nowarn

class LibraryI(platformSpecific: LibraryI.PlatformSpecific):
  trait Library[+L]:
    val handles: IArray[MethodHandle]
    val varGens: IArray[Seq[TypeDescriptor] => MethodHandle]
    val addresses: IArray[Object]

  object Library:
    inline def derived[L]: Library[L] = new Library[L]:
      val lookup = LibraryI.getLookup[L](platformSpecific)
      val addresses = LibraryI.getMethodAddress[L](lookup)
      val (handles, varGens) =
        MethodHandleTools.calculateMethodHandles[L](platformSpecific, addresses)

    inline def binding[R]: R =
      ${
        LibraryI.bindingImpl[R, Library]
      }

object LibraryI:
  trait PlatformSpecific:
    def getDowncall(
        address: Object,
        descriptor: FunctionDescriptor
    ): MethodHandle

    def getLocalLookup(name: String): Lookup
    def getLibraryPathLookup(name: String): Lookup
    def getStandardLibLookup: Lookup
    def getResourceLibLookup(location: String): Lookup

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused local definition")
  def checkMethodIsCompatible(using q: Quotes)(s: q.reflect.Symbol): Unit =
    import quotes.reflect.*

    s.tree match
      case DefDef(name, paramClauses, returnType, _) =>
        if paramClauses.size <= 2 then () else report.errorAndAbort("")

        paramClauses.zipWithIndex.foreach {
          case (TypeParamClause(typDefs), 0) =>
            typDefs.map(_.rhs).foreach {
              case TypeBoundsTree(Inferred(), Inferred()) => ()
              case TypeBoundsTree(_, _) =>
                report.errorAndAbort(
                  "Type bounds aren't supported on method bindings"
                )
            }
          case (TermParamClause(valDefs), i) if i == paramClauses.size - 1 =>
            valDefs.foreach { vd =>
              vd.tpt.tpe.asType match
                case '[Seq[Variadic]] =>
                  ()
                case '[a] =>
                  Expr
                    .summon[MethodCompatible[a]]
                    .map(_ => ())
                    .getOrElse(
                      report.errorAndAbort(
                        s"Type ${Type.show[a]} isn't compatible with native bindings."
                      )
                    )
            }
          case _ =>
            report.errorAndAbort("Method bindings cannot be curried")

            returnType.tpe.asType.match {
              case '[Unit] => ()
              case '[a] =>
                Expr
                  .summon[MethodCompatible[a]]
                  .map(_ => ())
                  .getOrElse(
                    report.errorAndAbort(
                      s"Return type ${Type.show[a]} isn't compatible with native bindings."
                    )
                  )
            }

        }

  def getReturnType(using q: Quotes)(s: quotes.reflect.Symbol) =
    import quotes.reflect.*
    if s.isDefDef then
      s.typeRef.translucentSuperType.asMatchable match
        case TypeLambda(_, _, ret: LambdaType) => ret.resType
        case ret: LambdaType                   => ret.resType
    else report.errorAndAbort("This symbol isn't a method!")

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused local definition")
  def needsAllocator(using q: Quotes)(s: q.reflect.Symbol): Boolean =
    import quotes.reflect.*

    getReturnType(s).asType match
      case '[r & Product] =>
        true
      case _ => false

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused implicit parameter")
  @nowarn("msg=unused local definition")
  def bindingImpl[R, L[_] <: LibraryI#Library[?]](using q: Quotes)(using
      Type[R],
      Type[L]
  ) =
    import quotes.reflect.*
    val owningClass = MacroHelpers.findOwningClass(Symbol.spliceOwner)
    val library = LibraryI.getLibrary[L](owningClass)

    val methodSymbol = MacroHelpers.findOwningMethod(Symbol.spliceOwner)
    val methodPositionExpr = MacroHelpers
      .getMethodSymbols(owningClass)
      .zipWithIndex
      .find((s, _) => methodSymbol == s)
      .map((_, i) => Expr(i))
      .getOrElse(
        report.errorAndAbort("Couldn't find method in declared methods?")
      )

    val methodHandle = '{ $library.handles($methodPositionExpr) }
    val methodHandleGen = '{ $library.varGens($methodPositionExpr) }
    val address = '{ $library.addresses($methodPositionExpr) }
    checkMethodIsCompatible(methodSymbol)

    val transitionModule = Expr
      .summon[TransitionModule]
      .getOrElse(report.errorAndAbort("Need transition module!!"))
    val prefix: List[Expr[Allocator => Any]] =
      if needsAllocator(methodSymbol) then
        List(
          '{ (a: Allocator) => $transitionModule.methodArgument(a) }
        )
      else Nil

    val inputs =
      prefix ++ MacroHelpers
        .getInputsAndOutputType(methodSymbol)
        ._1

    val standardInputs: List[Expr[Allocator => Any]] =
      prefix ++ MacroHelpers
        .getInputsAndOutputType(methodSymbol)
        ._1
        .filter(!_.isExprOf[Seq[Variadic]])
        .map { case '{ $e: t } =>
          TypeRepr.of[t].widen.asType match
            case '[widened] =>
              e -> Expr
                .summon[DescriptorOf[widened]]
                .getOrElse(
                  report.errorAndAbort(
                    s"No descriptor found for ${Type.show[widened]}"
                  )
                )
        }
        .map { case (expr, desc) =>
          '{ (alloc: Allocator) =>
            $transitionModule.methodArgument($desc.descriptor, $expr, alloc)
          }
        }
    val rTransition: Expr[Object | Null => R] = Type.of[R] match
      case '[Unit] =>
        '{ (_: Object | Null) => () }.asExprOf[Object | Null => R]
      case '[r] =>
        Expr
          .summon[DescriptorOf[R]]
          .map(e =>
            '{ (obj: Object | Null) =>
              $transitionModule.methodReturn[R]($e.descriptor, obj.nn)
            }
          )
          .getOrElse(
            report.errorAndAbort(
              s"No descriptor found for return type ${Type.show[R]}"
            )
          )

    val code: Expr[R] =
      if inputs.size == standardInputs.size then
        val methodInvoke =
          '{ (a: Allocator) =>
            ${
              MethodHandleTools.invokeArguments[R](
                methodHandle,
                standardInputs.map(exp => '{ $exp(a) }).map(Expr.betaReduce)
              )
            }
          }

        val scopeThing = Expr
          .summon[TempScope]
          .getOrElse(report.errorAndAbort("need temp allocator in scope"))

        Expr
          .summon[DescriptorOf[R]]
          .map(e => '{ $transitionModule.methodReturn[R]($e.descriptor, _) })
        val subCode = '{
          Scope.temp(using $scopeThing)((a: Allocator) ?=>
            $rTransition($methodInvoke(a))
          )
        }
        subCode
      else
        val varargs = inputs.last.asExprOf[Seq[Variadic]]
        val scopeThing = Expr
          .summon[TempScope]
          .getOrElse(report.errorAndAbort("need temp allocator in scope"))
        '{
          Scope.temp(using $scopeThing)((a: Allocator) ?=>
            ${
              val normalInputs = Expr.ofSeq(standardInputs.map(e => '{ $e(a) }))
              val totalInputs = '{
                $normalInputs ++ MethodHandleTools.getVariadicExprs($varargs)(
                  using $transitionModule
                )
              }

              '{
                $rTransition(
                  ${
                    MethodHandleTools.invokeVariadicArguments(
                      methodHandleGen,
                      totalInputs,
                      '{ MethodHandleTools.getVariadicContext($varargs) }
                    )
                  }
                )
              }
            }
          )
        }
    report.info(
      s"""|Binding has return that requires allocator: ${needsAllocator(
           methodSymbol
         )}
          |Mapped inputs: ${standardInputs.map(
           _.asTerm.show(using Printer.TreeShortCode)
         )}
          |Generated code: ${code.asTerm.show(using
           Printer.TreeShortCode
         )}""".stripMargin
    )
    code

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused local definition")
  def getLibrary[L[_]](using q: Quotes)(using Type[L])(
      owningClass: q.reflect.Symbol
  ): Expr[L[Any]] =
    import quotes.reflect.*

    TypeRepr.of[L].appliedTo(owningClass.typeRef).asType match
      case '[l] =>
        Expr.summon[l] match
          case None =>
            report.errorAndAbort(
              s"Cannot find library ${Type.show[l]}"
            )
          case Some(exp) => exp.asExprOf[L[Any]]

  inline def getLookup[L](platformSpecific: PlatformSpecific): Lookup = ${
    getLookupImpl[L]('platformSpecific)
  }

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused implicit parameter")
  def getLookupImpl[L](
      platformSpecificExpr: Expr[PlatformSpecific]
  )(using Quotes, Type[L]) =
    val name: LibraryLocation = LibraryName.libraryName[L]
    name match
      case LibraryLocation.Standard =>
        '{ $platformSpecificExpr.getStandardLibLookup }
      case LibraryLocation.Local(s) =>
        '{ $platformSpecificExpr.getLocalLookup(${ Expr(s) }) }
      case LibraryLocation.Path(s) =>
        '{ $platformSpecificExpr.getLibraryPathLookup(${ Expr(s) }) }
      case LibraryLocation.Resource(s) =>
        '{ $platformSpecificExpr.getResourceLibLookup(${ Expr(s) }) }

  inline def getMethodAddress[L](l: Lookup) = ${
    getMethodAddressImpl[L]('l)
  }

  // todo: get rid of this once bug https://github.com/lampepfl/dotty/issues/16863 is fixed
  @nowarn("msg=unused implicit parameter")
  def getMethodAddressImpl[L](l: Expr[Lookup])(using Quotes, Type[L]) =
    import quotes.reflect.*

    val methodList =
      MacroHelpers
        .getMethodSymbols(MacroHelpers.getClassSymbol[L])
        .map(s => Expr(s.name))

    report.info(Type.show[L])

    val addresses = methodList.map(method =>
      '{
        val s = $method
        $l.lookup(s)
        // .getOrElse(throw Error(s"Can't find method ${s}"))
      }
    )

    '{ IArray(${ Varargs(addresses) }*) }
