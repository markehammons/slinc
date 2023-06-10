package fr.hammons.slinc.modules

import fr.hammons.slinc.*
import scala.collection.concurrent.TrieMap
import java.lang.invoke.MethodHandle
import scala.reflect.ClassTag
import fr.hammons.slinc.fnutils.Fn
import scala.quoted.*
import java.lang.reflect.Modifier
import jdk.incubator.foreign.*

given readWriteModule17: ReadWriteModule with
  // todo: eliminate this

  def writeFn(
      typeDescriptor: TypeDescriptor
  ): MemWriter[typeDescriptor.Inner] = ???

  override val intWritingExpr: (Quotes) ?=> Expr[MemWriter[Int]] = ???

  val fnCache: TrieMap[CFunctionDescriptor, Mem => ?] =
    TrieMap.empty

  val readerCache = DependentTrieMap[Reader]

  val arrayReaderCache = DependentTrieMap[ArrayReader]

  val writerCache = DependentTrieMap[MemWriter]

  val arrayWriterCache = DependentTrieMap[[I] =>> MemWriter[Array[I]]]

  val byteWriter = (mem, offset, value) => mem.writeByte(value, offset)
  val shortWriter = (mem, offset, value) => mem.writeShort(value, offset)

  val intWriter = (mem, offset, value) => mem.writeInt(value, offset)

  val longWriter = (mem, offset, value) => mem.writeLong(value, offset)

  val floatWriter = (mem, offset, value) => mem.writeFloat(value, offset)

  val doubleWriter = (mem, offset, value) => mem.writeDouble(value, offset)

  val memWriter = (mem, offset, value) => mem.writeAddress(value, offset)

  val byteReader = (mem, offset) => mem.readByte(offset)
  val shortReader = (mem, offset) => mem.readShort(offset)
  val intReader = (mem, offset) => mem.readInt(offset)
  val longReader = (mem, offset) => mem.readLong(offset)

  val floatReader = (mem, offset) => mem.readFloat(offset)
  val doubleReader = (mem, offset) => mem.readDouble(offset)

  val memReader = (mem, offset) => mem.readAddress(offset)

  def unionReader(
      typeDescriptor: TypeDescriptor
  ): Reader[CUnion[? <: NonEmptyTuple]] =
    val size = descriptorModule17.sizeOf(typeDescriptor)
    (mem, offset) =>
      Scope17.createInferredScope(alloc ?=>
        val newMem = alloc.allocate(typeDescriptor, 1)
        newMem.copyFrom(mem.offset(offset).resize(size))

        new CUnion(newMem)
      )

  def unionWriter(td: TypeDescriptor): MemWriter[CUnion[? <: NonEmptyTuple]] =
    val size = descriptorModule17.sizeOf(td)
    (mem, offset, value) => mem.offset(offset).resize(size).copyFrom(value.mem)

  arrayWriterCache
    .addOne(
      ByteDescriptor,
      (mem: Mem, offset: Bytes, value: Array[Byte]) =>
        mem.writeByteArray(value, offset)
    )
  arrayWriterCache
    .addOne(
      IntDescriptor,
      (mem: Mem, offset: Bytes, value: Array[Int]) =>
        mem.writeIntArray(value, offset)
    )

  override def read(
      memory: Mem,
      offset: Bytes,
      typeDescriptor: TypeDescriptor
  ): typeDescriptor.Inner = readerCache
    .getOrElseUpdate(typeDescriptor, typeDescriptor.reader)(memory, offset)

  override def readFn[A](
      mem: Mem,
      descriptor: CFunctionDescriptor,
      fn: => MethodHandle => Mem => A
  )(using Fn[A, ?, ?]): A =
    fnCache
      .getOrElseUpdate(
        descriptor,
        fn(LinkageModule17.getDowncall(descriptor, Nil))
      )
      .asInstanceOf[Mem => A](mem)

  override def readArray[A](memory: Mem, offset: Bytes, size: Int)(using
      DescriptorOf[A],
      ClassTag[A]
  ): Array[A] =
    val desc = DescriptorOf[A]
    arrayReaderCache.getOrElseUpdate(desc, desc.arrayReader)(
      memory,
      offset,
      size
    )

  override def write(
      memory: Mem,
      offset: Bytes,
      typeDescriptor: TypeDescriptor,
      value: typeDescriptor.Inner
  ): Unit = writerCache.getOrElseUpdate(
    typeDescriptor,
    typeDescriptor.writer
  )(memory, offset, value)

  def asExprOf[A](expr: Expr[?])(using Quotes, Type[A]) =
    if expr.isExprOf[A] then expr.asExprOf[A]
    else '{ $expr.asInstanceOf[A] }.asExprOf[A]

  def canBeUsedDirectly(clazz: Class[?]): Boolean =
    val enclosingClass = clazz.getEnclosingClass()
    if clazz.getCanonicalName() == null then false
    else if enclosingClass == null && clazz
        .getEnclosingConstructor() == null && clazz.getEnclosingMethod() == null
    then true
    else if canBeUsedDirectly(enclosingClass.nn) && Modifier.isStatic(
        clazz.getModifiers()
      ) && Modifier.isPublic(clazz.getModifiers())
    then true
    else false

  def foldOffsets(offsets: Seq[Expr[Bytes]])(using Quotes): Expr[Bytes] =
    val (constants, references) = offsets.partition(_.value.isDefined)
    val constantOffset = Expr(constants.map(_.valueOrAbort).sum)
    val referenceOffset = references.reduceLeftOption((a, b) => '{ $a + $b })
    referenceOffset
      .map(refOff => '{ $refOff + $constantOffset })
      .getOrElse(constantOffset)

  def writeExprHelper[A](
      typeDescriptor: TypeDescriptor,
      mem: Expr[MemorySegment],
      offsetExprs: Seq[Expr[Bytes]],
      value: Expr[A]
  )(using Quotes, Type[A]): Expr[Unit] =
    import quotes.reflect.*
    typeDescriptor match
      case ByteDescriptor  => ???
      case ShortDescriptor => ???
      case IntDescriptor =>
        '{
          MemoryAccess.setIntAtOffset(
            $mem,
            ${ foldOffsets(offsetExprs) }.toLong,
            ${ asExprOf[Int](value) }
          )
        }
      case LongDescriptor =>
        '{
          MemoryAccess.setLongAtOffset(
            $mem,
            ${ foldOffsets(offsetExprs) }.toLong,
            ${ asExprOf[Long](value) }
          )
          // $mem.writeLong(
          //   ${ asExprOf[Long](value) },
          //   ${ foldOffsets(offsetExprs) }
          // )
        }
      case FloatDescriptor =>
        '{
          MemoryAccess.setFloatAtOffset(
            $mem,
            ${ foldOffsets(offsetExprs) }.toLong,
            ${ asExprOf[Float](value) }
          )
          // $mem.writeFloat(
          //   ${ asExprOf[Float](value) },
          //   ${ foldOffsets(offsetExprs)}
          // )
        }
      case DoubleDescriptor => ???
      case PtrDescriptor    => ???
      case sd: StructDescriptor if canBeUsedDirectly(sd.clazz) =>
        println(s"compiling $sd")
        val fields =
          Symbol.classSymbol(sd.clazz.getCanonicalName().nn).caseFields

        println("calculated fields")
        val offsets =
          descriptorModule17.memberOffsets(sd.members.map(_.descriptor))

        println("calculated offsets")

        val fns = sd.members
          .zip(offsets)
          .zipWithIndex
          .map {
            case (
                  (StructMemberDescriptor(childDescriptor, name), childOffset),
                  index
                ) =>
              (nv: Expr[A]) =>
                val childField = Select(nv.asTerm, fields(index)).asExpr
                val totalOffset = offsetExprs :+ Expr(childOffset)

                writeExprHelper(childDescriptor, mem, totalOffset, childField)
          }
          .toList

        println("fns complete")

        val code = TypeRepr.typeConstructorOf(sd.clazz).asType match
          case '[a & Product] =>
            val writes = fns.map(_(value))
            Expr.block(writes.init, writes.last)
        println(code.show)
        code
      case sd: StructDescriptor =>
        val offsets =
          descriptorModule17.memberOffsets(sd.members.map(_.descriptor))
        val fns = sd.members
          .zip(offsets)
          .zipWithIndex
          .map {
            case ((StructMemberDescriptor(td, name), childOffset), index) =>
              (nv: Expr[Product]) =>
                val childField = '{ $nv.productElement(${ Expr(index) }) }
                val totalOffset = offsetExprs :+ Expr(childOffset)

                writeExprHelper(td, mem, totalOffset, childField)
          }
          .toList

        '{
          val a: Product = ${ asExprOf[Product](value) }

          ${
            Expr.block(fns.map(_('a)), '{})
          }
        }

      case AliasDescriptor(real) =>
        writeExprHelper(real, mem, offsetExprs, value)
      case VaListDescriptor                => ???
      case CUnionDescriptor(possibleTypes) => ???
      case SetSizeArrayDescriptor(td, x)   => ???

  def writeExpr[A](
      typeDescriptor: TypeDescriptor
  )(using Quotes, ClassTag[A], A =:= typeDescriptor.Inner): Expr[MemWriter[A]] =
    import quotes.reflect.*
    val output = TypeRepr
      .typeConstructorOf(summon[ClassTag[A]].runtimeClass)
      .asType match
      case '[a] =>
        '{ (mem: Mem, offset: Bytes, value: a) =>
          val memsegment = mem.asBase.asInstanceOf[MemorySegment]
          ${
            writeExprHelper(
              typeDescriptor,
              'memsegment,
              Seq('offset),
              '{ value }
            )
          }
        }

    given Type[A] = TypeRepr
      .typeConstructorOf(summon[ClassTag[A]].runtimeClass)
      .asType
      .asInstanceOf[Type[A]]
    output.asExprOf[MemWriter[A]]

  def writeArrayExpr(typeDescriptor: TypeDescriptor)(using
      Quotes
  ): Expr[MemWriter[Array[Any]]] =
    val elemLength = Expr(typeDescriptor.size)
    '{ (mem: Mem, offset: Bytes, value: Array[Any]) =>
      var x = 0
      val ms = mem.asBase.asInstanceOf[MemorySegment]
      while x < value.length do
        ${
          writeExprHelper(
            typeDescriptor,
            'ms,
            Seq(
              '{
                ($elemLength * x)
              },
              '{ offset }
            ),
            '{ value(x) }
          )
        }
        x += 1
    }

  def writeArray[A](memory: Mem, offset: Bytes, value: Array[A])(using
      DescriptorOf[A]
  ): Unit =
    val desc = DescriptorOf[A]
    arrayWriterCache.getOrElseUpdate(desc, desc.arrayWriter)(
      memory,
      offset,
      value
    )
