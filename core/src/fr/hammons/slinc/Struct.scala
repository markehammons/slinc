package fr.hammons.slinc

import scala.deriving.Mirror
import scala.compiletime.{
  uninitialized,
  erasedValue,
  summonInline,
  constValueTuple
}
import java.util.concurrent.atomic.AtomicReference
import scala.reflect.ClassTag
import modules.DescriptorModule
import fr.hammons.slinc.modules.TransitionModule

class StructI(
    jitManager: JitManager
)(using DescriptorModule, TransitionModule):
  /** Summons up Descriptors for the members of Product A
    *
    * @tparam A
    *   The product type to summon a list of descriptors for
    * @return
    *   List[TypeDescriptor]
    */
  private inline def memberDescriptors[A](using
      m: Mirror.ProductOf[A]
  ): List[TypeDescriptor] =
    memberDescriptorsHelper[m.MirroredElemTypes]
  private inline def memberDescriptorsHelper[T <: Tuple]: List[TypeDescriptor] =
    inline erasedValue[T] match
      case _: (h *: t) =>
        summonInline[DescriptorOf[h]].descriptor :: memberDescriptorsHelper[t]
      case _: EmptyTuple => Nil

  /** Summons up the names of members of the Product A
    *
    * @tparam A
    *   A product type representing a C struct.
    * @return
    */
  private inline def memberNames[A](using m: Mirror.ProductOf[A]) =
    constValueTuple[m.MirroredElemLabels].toArray.map(_.toString())

  trait Struct[A <: Product]
      extends DescriptorOf[A],
        Send[A],
        Receive[A],
        MethodCompatible[A]

  object Struct:
    inline def derived[A <: Product](using
        m: Mirror.ProductOf[A],
        ct: ClassTag[A]
    ) = new Struct[A]:
      val descriptor: StructDescriptor = StructDescriptor(
        memberDescriptors[A].view
          .zip(memberNames[A])
          .map(StructMemberDescriptor.apply)
          .toList,
        ct.runtimeClass,
        m.fromProduct(_)
      )

      private val offsetsArray = descriptor.offsets

      private var sender: Send[A] = uninitialized

      private var receiver: Receive[A] = uninitialized

      jitManager.jitc(
        Send.compileTime[A](offsetsArray),
        Send.staged[A](descriptor),
        sender = _
      )

      jitManager.jitc(
        Receive.compileTime[A](offsetsArray),
        Receive.staged[A](descriptor),
        receiver = _
      )

      final def to(mem: Mem, offset: Bytes, a: A): Unit =
        sender.to(mem, offset, a)

      final def from(mem: Mem, offset: Bytes): A =
        receiver.from(mem, offset).asInstanceOf[A]

      summon[TransitionModule].registerMethodArgumentTransition[A](
        this.descriptor,
        Allocator ?=> in(_)
      )
      summon[TransitionModule]
        .registerMethodReturnTransition[A](this.descriptor, out)
      final def in(a: A)(using alloc: Allocator): Object =
        val mem = alloc.allocate(this.descriptor, 1)
        to(mem, Bytes(0), a)
        summon[TransitionModule].methodArgument(mem).asInstanceOf[Object]

      final def out(a: Object): A =
        val mem = summon[TransitionModule].memReturn(a)
        from(mem, Bytes(0))
