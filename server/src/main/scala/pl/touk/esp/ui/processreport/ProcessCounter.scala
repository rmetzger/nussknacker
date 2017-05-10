package pl.touk.esp.ui.processreport

import pl.touk.esp.engine.canonicalgraph.CanonicalProcess
import pl.touk.esp.engine.canonicalgraph.canonicalnode._
import pl.touk.esp.ui.process.displayedgraph.displayablenode.ProcessAdditionalFields
import pl.touk.esp.ui.process.subprocess.SubprocessRepository
import shapeless.syntax.typeable._

class ProcessCounter(subprocessRepository: SubprocessRepository) {


  def computeCounts(canonicalProcess: CanonicalProcess, counts: Map[String, RawCount]) : Map[String, NodeCount] = {

    def computeCounts(prefixes: List[String])(nodes: Iterable[CanonicalNode]) : Map[String, NodeCount] = {

      val computeCountsSamePrefixes = computeCounts(prefixes) _

      def nodeCount(id: String, subProcessCounts: Map[String, NodeCount] = Map()) = {
        val countId = (prefixes :+ id).mkString("-")
        val count = counts.getOrElse(countId, RawCount(0L, 0L))
        NodeCount(count.all, count.errors, subProcessCounts)
      }

      nodes.flatMap {
        case FlatNode(node) => Map(node.id -> nodeCount(node.id))
        case FilterNode(node, nextFalse) => computeCountsSamePrefixes(nextFalse) + (node.id -> nodeCount(node.id))
        case SwitchNode(node, nexts, defaultNext) =>
          computeCountsSamePrefixes(nexts.flatMap(_.nodes)) ++ computeCountsSamePrefixes(defaultNext) + (node.id -> nodeCount(node.id))
        case SplitNode(node, nexts) => computeCountsSamePrefixes(nexts.flatten) + (node.id -> nodeCount(node.id))
        case Subprocess(node, outputs) =>
          computeCountsSamePrefixes(outputs.values.flatten) + (node.id -> nodeCount(node.id,
            //TODO: walidacja czy proces istnieje
            computeCounts(prefixes :+ node.id)(subprocessRepository.get(node.ref.id).get.nodes)))
      }.toMap

    }
    val valuesWithoutGroups = computeCounts(List())(canonicalProcess.nodes)

    val valuesForGroups: Map[String, NodeCount] = computeValuesForGroups(valuesWithoutGroups, canonicalProcess)
    valuesWithoutGroups ++ valuesForGroups
  }


  private def computeValuesForGroups(valuesWithoutGroups: Map[String, NodeCount], canonicalProcess: CanonicalProcess) = {
    val groups = canonicalProcess.metaData.additionalFields.flatMap(_.cast[ProcessAdditionalFields]).map(_.groups).getOrElse(Set())

    groups.map { group =>
      val valuesForGroup = valuesWithoutGroups.filterKeys(nodeId => group.nodes.contains(nodeId))
      val all = valuesForGroup.values.map(_.all).max
      val errors = valuesForGroup.values.map(_.errors).max
      group.id -> NodeCount(all, errors)
    }.toMap
  }
}


case class RawCount(all: Long, errors: Long)

case class NodeCount(all: Long, errors: Long, subProcessCounts: Map[String, NodeCount] = Map())