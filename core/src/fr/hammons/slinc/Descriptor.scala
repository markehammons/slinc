package fr.hammons.slinc

import scala.quoted.*
import fr.hammons.slinc.modules.DescriptorModule

final case class Descriptor(
    inputLayouts: Seq[DataLayout],
    variadicLayouts: Seq[DataLayout],
    outputLayout: Option[DataLayout]
):
  def addVarargs(args: DataLayout*) =
    Descriptor(inputLayouts, args, outputLayout)

object Descriptor:
  // grabs a description of a method from its definition. Ignores Seq[Variadic] arguments.
  def fromDefDef(using q: Quotes)(symbol: q.reflect.Symbol) =
    import quotes.reflect.*
    val (inputRefs, outputType) = MacroHelpers.getInputsAndOutputType(symbol)

    val inputLayouts = Expr.ofSeq(
      inputRefs
        .filter {
          case '{ ${ _ }: Seq[Variadic] } => false
          case _                          => true
        }
        .map { case '{ ${ _ }: a } =>
          DescriptorOf.getDescriptorFor[a]
        }
    )

    val dm = Expr
      .summon[DescriptorModule]
      .getOrElse(report.errorAndAbort(s"Cannot find DescriptorModule"))

    val outputLayout = outputType match
      case '[Unit] => '{ None }
      case '[o] =>
        val layout = DescriptorOf.getDescriptorFor[o]
        '{ Some($dm.toDataLayout($layout)) }

    '{
      Descriptor($inputLayouts.map($dm.toDataLayout), Seq.empty, $outputLayout)
    }

  inline def fromFunction[A] = ${
    fromFunctionImpl[A]
  }
  private[slinc] def fromFunctionImpl[A](using Quotes, Type[A]) =
    val (inputTypes, outputType) = MacroHelpers.getInputTypesAndOutputTypes[A]

    val inputLayouts = Expr.ofSeq(inputTypes.map { case '[a] =>
      DescriptorOf.getDescriptorFor[a]
    })

    val dm = Expr.summon[DescriptorModule].getOrElse(???)

    val outputLayout = outputType match
      case '[Unit] => '{ None }
      case '[o] =>
        val layout = DescriptorOf.getDescriptorFor[o]
        '{ Some($dm.toDataLayout($layout)) }

    '{
      Descriptor($inputLayouts.map($dm.toDataLayout), Seq.empty, $outputLayout)
    }
