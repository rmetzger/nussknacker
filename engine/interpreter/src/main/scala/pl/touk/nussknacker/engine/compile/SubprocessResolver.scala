package pl.touk.nussknacker.engine.compile

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import pl.touk.nussknacker.engine.api.{MetaData, StreamMetaData}
import pl.touk.nussknacker.engine.canonicalgraph.canonicalnode.{CanonicalNode, FlatNode}
import pl.touk.nussknacker.engine.canonicalgraph.{CanonicalProcess, canonicalnode}
import pl.touk.nussknacker.engine.compile.ProcessCompilationError.{InvalidSubprocess, NodeId, UnknownSubprocess, UnknownSubprocessOutput}
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.engine.graph.subprocess.SubprocessRef

object SubprocessResolver {
  def apply(subprocesses: Iterable[CanonicalProcess]): SubprocessResolver =
    SubprocessResolver(subprocesses.map(a => a.metaData.id -> a).toMap)
}

case class SubprocessResolver(subprocesses: Map[String, CanonicalProcess]) {

  type CompilationValid[A] = ValidatedNel[ProcessCompilationError, A]

  private val syntax = ValidatedSyntax[ProcessCompilationError]

  import syntax.A

  def resolve(canonicalProcess: CanonicalProcess): ValidatedNel[ProcessCompilationError, CanonicalProcess] =
    resolveCanonical(List())(canonicalProcess.nodes).map(res => canonicalProcess.copy(nodes = res))

  private def resolveCanonical(idPrefix: List[String]) :List[CanonicalNode] => ValidatedNel[ProcessCompilationError, List[CanonicalNode]] = {
    iterateOverCanonicals({
      case canonicalnode.Subprocess(data, nextNodes) =>
        subprocesses.get(data.ref.id) match {
          case Some(CanonicalProcess(MetaData(id, _, _, _), _, FlatNode(SubprocessInputDefinition(_, parameters, _))::nodes)) =>
            checkProcessParameters(data.ref, parameters.map(_.name), data.id).andThen { _ =>
              val nextResolvedV = nextNodes.map { case (k, v) =>
                resolveCanonical(idPrefix)(v).map((k, _))
              }.toList.sequence[CompilationValid, (String, List[CanonicalNode])].map(_.toMap)
              val subResolvedV = resolveCanonical(idPrefix :+ data.id)(nodes)
              A.map2(nextResolvedV, subResolvedV) { (nodeResolved, nextResolved) =>
                replaceCanonicalList(nodeResolved)(nextResolved)
              }.andThen(identity).map(replaced => FlatNode(NodeDataFun.nodeIdPrefix(idPrefix)(data)) :: replaced)
            }
          case Some(_) => Invalid(NonEmptyList.of(InvalidSubprocess(data.id, data.ref.id)))
          case _ => Invalid(NonEmptyList.of(UnknownSubprocess(data.id, data.ref.id)))
        }
    }, NodeDataFun.nodeIdPrefix(idPrefix))
  }

  private def checkProcessParameters(ref: SubprocessRef, parameters: List[String], nodeId: String): CompilationValid[Unit] = {
    Validations.validateParameters[ProcessCompilationError](parameters, ref.parameters.map(_.name))(NodeId(nodeId))
  }

  private def replaceCanonicalList(replacement: Map[String, List[CanonicalNode]]): List[CanonicalNode] => CompilationValid[List[CanonicalNode]] = {

    iterateOverCanonicals({
      case FlatNode(SubprocessOutputDefinition(id, name, add)) => replacement.get(name) match {
        case Some(nodes) => Valid(FlatNode(SubprocessOutput(id, name, add)) :: nodes)
        case None => Invalid(NonEmptyList.of(UnknownSubprocessOutput(name, id)))
      }
    }, NodeDataFun.id)
  }

  private def iterateOverCanonicals(action: PartialFunction[CanonicalNode, CompilationValid[List[CanonicalNode]]], dataAction: NodeDataFun)  =
    (l:List[CanonicalNode]) => l.map(iterateOverCanonical(action, dataAction)).sequence[CompilationValid, List[CanonicalNode]].map(_.flatten)

  private def iterateOverCanonical(action: PartialFunction[CanonicalNode, CompilationValid[List[CanonicalNode]]],
                                     dataAction: NodeDataFun)
  : (CanonicalNode => CompilationValid[List[CanonicalNode]]) = cnode => {
    val listFun = iterateOverCanonicals(action, dataAction)
      action.applyOrElse[CanonicalNode, CompilationValid[List[CanonicalNode]]](cnode, {
        case FlatNode(data) => Valid(List(FlatNode(dataAction(data))))
        case canonicalnode.FilterNode(data, nextFalse) =>
          listFun(nextFalse).map(canonicalnode.FilterNode(dataAction(data), _)).map(List(_))
        case canonicalnode.SplitNode(data, nexts) =>
          nexts.map(listFun).sequence[CompilationValid, List[CanonicalNode]]
            .map(canonicalnode.SplitNode(dataAction(data), _)).map(List(_))
        case canonicalnode.SwitchNode(data, nexts, defaultNext) =>
          A.map2(nexts.map(cas => listFun(cas.nodes).map(replaced => canonicalnode.Case(cas.expression, replaced))).sequence[CompilationValid, canonicalnode.Case],
            listFun(defaultNext)) { (resolvedCases, resolvedDefault) =>
            List(canonicalnode.SwitchNode(dataAction(data), resolvedCases, resolvedDefault))
          }
        case canonicalnode.Subprocess(data, nodes) =>
          nodes.map { case (k, v) =>
            listFun(v).map(k -> _)
          }.toList.sequence[CompilationValid, (String, List[CanonicalNode])].map(replaced => List(canonicalnode.Subprocess(dataAction(data), replaced.toMap)))
      }
    )
  }

  trait NodeDataFun {
    def apply[T<:NodeData](n: T) : T
  }

  object NodeDataFun {
    val id = new NodeDataFun {
      override def apply[T <: NodeData](n: T): T = n
    }
    def nodeIdPrefix(prefix:List[String]) = new NodeDataFun {
      override def apply[T <: NodeData](n: T): T = {
        pl.touk.nussknacker.engine.graph.node.prefixNodeId(prefix, n)
      }
    }
  }

}
