package org.scalamock.clazz

import org.scalamock.MockContext
import org.scalamock.function._
import org.scalamock.util.MacroUtils

import scala.reflect.macros.blackbox.Context

//! TODO - get rid of this nasty two-stage construction when https://issues.scala-lang.org/browse/SI-5521 is fixed
class MockMaker[C <: Context](val ctx: C) {
  class MockMakerInner[T: ctx.WeakTypeTag](mockContext: ctx.Expr[MockContext], stub: Boolean) {
    import ctx.universe._
    import Flag._
    import definitions._
    import scala.language.reflectiveCalls

    val utils = new MacroUtils[ctx.type](ctx)
    import utils._

    def mockFunctionClass(paramCount: Int): Type = paramCount match {
      case 0 => typeOf[MockFunction0[_]]
      case 1 => typeOf[MockFunction1[_, _]]
      case 2 => typeOf[MockFunction2[_, _, _]]
      case 3 => typeOf[MockFunction3[_, _, _, _]]
      case 4 => typeOf[MockFunction4[_, _, _, _, _]]
      case 5 => typeOf[MockFunction5[_, _, _, _, _, _]]
      case 6 => typeOf[MockFunction6[_, _, _, _, _, _, _]]
      case 7 => typeOf[MockFunction7[_, _, _, _, _, _, _, _]]
      case 8 => typeOf[MockFunction8[_, _, _, _, _, _, _, _, _]]
      case 9 => typeOf[MockFunction9[_, _, _, _, _, _, _, _, _, _]]
      case _ => ctx.abort(ctx.enclosingPosition, "ScalaMock: Can't handle methods with more than 9 parameters (yet)")
    }

    def stubFunctionClass(paramCount: Int): Type = paramCount match {
      case 0 => typeOf[StubFunction0[_]]
      case 1 => typeOf[StubFunction1[_, _]]
      case 2 => typeOf[StubFunction2[_, _, _]]
      case 3 => typeOf[StubFunction3[_, _, _, _]]
      case 4 => typeOf[StubFunction4[_, _, _, _, _]]
      case 5 => typeOf[StubFunction5[_, _, _, _, _, _]]
      case 6 => typeOf[StubFunction6[_, _, _, _, _, _, _]]
      case 7 => typeOf[StubFunction7[_, _, _, _, _, _, _, _]]
      case 8 => typeOf[StubFunction8[_, _, _, _, _, _, _, _, _]]
      case 9 => typeOf[StubFunction9[_, _, _, _, _, _, _, _, _, _]]
      case _ => ctx.abort(ctx.enclosingPosition, "ScalaMock: Can't handle methods with more than 9 parameters (yet)")
    }

    def classType(paramCount: Int) = if (stub) stubFunctionClass(paramCount) else mockFunctionClass(paramCount)

    def isPathDependentThis(t: Type): Boolean = t match {
      case TypeRef(pre, _, _) => isPathDependentThis(pre)
      case ThisType(tpe) => tpe == typeToMock.typeSymbol
      case _ => false
    }

    /**
     *  Translates forwarder parameters into Trees.
     *  Also maps Java repeated params into Scala repeated params
     */
    def forwarderParamType(t: Type): Tree = t match {
      case TypeRef(pre, sym, args) if sym == JavaRepeatedParamClass =>
        TypeTree(internal.typeRef(pre, RepeatedParamClass, args))
      case TypeRef(pre, sym, args) if isPathDependentThis(t) =>
        AppliedTypeTree(Ident(TypeName(sym.name.toString)), args map TypeTree _)
      case _ =>
        TypeTree(t)
    }

    /**
     *  Translates mock function parameters into Trees.
     *  The difference between forwarderParamType is that:
     *  T* and T... are translated into Seq[T]
     *
     *  see issue #24
     */
    def mockParamType(t: Type): Tree = t match {
      case TypeRef(pre, sym, args) if sym == JavaRepeatedParamClass || sym == RepeatedParamClass =>
        AppliedTypeTree(Ident(typeOf[Seq[_]].typeSymbol), args map TypeTree _)
      case TypeRef(pre, sym, args) if isPathDependentThis(t) =>
        AppliedTypeTree(Ident(TypeName(sym.name.toString)), args map TypeTree _)
      case _ =>
        TypeTree(t)
    }

    def methodsNotInObject =
      typeToMock.members filter (m => m.isMethod && !isMemberOfObject(m)) map (_.asMethod)

    //! TODO - This is a hack, but it's unclear what it should be instead. See
    //! https://groups.google.com/d/topic/scala-user/n11V6_zI5go/discussion
    def resolvedType(m: Symbol): Type =
      m.typeSignatureIn(internal.superType(internal.thisType(typeToMock.typeSymbol), typeToMock))

    def buildForwarderParams(methodType: Type) =
      paramss(methodType) map { params =>
        params map { p =>
          ValDef(
            Modifiers(PARAM | (if (p.isImplicit) IMPLICIT else NoFlags)),
            TermName(p.name.toString),
            forwarderParamType(p.typeSignature),
            EmptyTree)
        }
      }

    // def <|name|>(p1: T1, p2: T2, ...): T = <|mockname|>(p1, p2, ...)
    def methodDef(m: MethodSymbol, methodType: Type, body: Tree): DefDef = {
      val params = buildForwarderParams(methodType)
      DefDef(
        Modifiers(OVERRIDE),
        m.name,
        m.typeParams map { p => internal.typeDef(p) },
        params,
        forwarderParamType(finalResultType(methodType)),
        body)
    }

    def methodImpl(m: MethodSymbol, methodType: Type, body: Tree): DefDef = {
      methodType match {
        case NullaryMethodType(_) => methodDef(m, methodType, body)
        case MethodType(_, _) => methodDef(m, methodType, body)
        case PolyType(_, _) => methodDef(m, methodType, body)
        case _ => ctx.abort(ctx.enclosingPosition,
          s"ScalaMock: Don't know how to handle ${methodType.getClass}. Please open a ticket at https://github.com/paulbutcher/ScalaMock/issues")
      }
    }

    def forwarderImpl(m: MethodSymbol): ValOrDefDef = {
      val mt = resolvedType(m)
      if (m.isStable) {
        ValDef(
          Modifiers(),
          TermName(m.name.toString),
          TypeTree(mt),
          castTo(literal(null), mt))
      } else {
        val body = applyListOn(
          Select(This(anon), mockFunctionName(m)), "apply",
          paramss(mt).flatten map { p => Ident(TermName(p.name.toString)) })
        methodImpl(m, mt, body)
      }
    }

    def mockFunctionName(m: MethodSymbol) = {
      val method = typeToMock.member(m.name).asTerm
      val index = method.alternatives.indexOf(m)
      assert(index >= 0)
      TermName("mock$" + m.name + "$" + index)
    }

    // val <|mockname|> = new MockFunctionN[T1, T2, ..., R](mockContext, '<|name|>)
    def mockMethod(m: MethodSymbol): ValDef = {
      val mt = resolvedType(m)
      val clazz = classType(paramCount(mt))
      val types = (paramTypes(mt) map mockParamType _) :+ mockParamType(finalResultType(mt))
      val name = applyOn(scalaSymbol, "apply", literal(m.name.toString))

      ValDef(Modifiers(),
        mockFunctionName(m),
        AppliedTypeTree(Ident(clazz.typeSymbol), types), // see issue #24
        callConstructor(
          New(AppliedTypeTree(Ident(clazz.typeSymbol), types)),
          mockContext.tree, name))
    }

    // def <init>() = super.<init>()
    def initDef =
      DefDef(
        Modifiers(),
        TermName("<init>"),
        List(),
        List(List()),
        TypeTree(),
        Block(
          List(callConstructor(Super(This(TypeName("")), TypeName("")))),
          Literal(Constant(()))))

    // new <|typeToMock|> { <|members|> }
    def anonClass(members: List[Tree]) =
      Block(
        List(
          ClassDef(
            Modifiers(FINAL),
            anon,
            List(),
            Template(
              List(TypeTree(typeToMock)),
              noSelfType,
              initDef +: members))),
        callConstructor(New(Ident(anon))))

    val typeToMock = weakTypeOf[T]
    val anon = TypeName("$anon")
    val methodsToMock = methodsNotInObject.filter { m =>
      !m.isConstructor && !m.isPrivate && m.privateWithin == NoSymbol &&
        !m.asInstanceOf[reflect.internal.HasFlags].hasFlag(reflect.internal.Flags.BRIDGE) &&
        (!(m.isStable || m.isAccessor) ||
          m.asInstanceOf[reflect.internal.HasFlags].isDeferred) //! TODO - stop using internal if/when this gets into the API
    }.toList
    val forwarders = methodsToMock map forwarderImpl _
    val mocks = methodsToMock map mockMethod _
    val members = forwarders ++ mocks

    def make() = {
      val result = castTo(anonClass(members), typeToMock)

      //        println("------------")
      //        println(showRaw(result))
      //        println("------------")
      //        println(show(result))
      //        println("------------")

      ctx.Expr(result)
    }
  }
}
