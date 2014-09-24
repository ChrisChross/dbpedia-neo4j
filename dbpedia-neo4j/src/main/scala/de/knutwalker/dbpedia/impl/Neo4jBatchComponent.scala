package de.knutwalker.dbpedia.impl

import java.util

import com.carrotsearch.hppc.{ ObjectLongMap, ObjectLongOpenHashMap, ObjectObjectOpenHashMap }
import de.knutwalker.dbpedia.importer.{ GraphComponent, MetricsComponent, SettingsComponent }
import org.neo4j.graphdb.{ DynamicLabel, DynamicRelationshipType, Label, RelationshipType }
import org.neo4j.helpers.collection.MapUtil
import org.neo4j.unsafe.batchinsert.{ BatchInserter, BatchInserters }

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

trait Neo4jBatchComponent extends GraphComponent {
  this: MetricsComponent ⇒

  type NodeType = Long
  type RelType = RelationshipType

  val graph: Graph = new Neo4jBatchGraph

  private final class Neo4jBatchGraph extends Graph {

    private[this] var inserter: BatchInserter = _

    private[this] val labels = new ObjectObjectOpenHashMap[String, Label](512)

    private[this] var resources: ObjectLongMap[String] = _
    private[this] var bnodes: ObjectLongMap[String] = _

    private[this] val p1 = new util.HashMap[String, AnyRef](1)
    private[this] val p2 = new util.HashMap[String, AnyRef](2)

    def startup(settings: SettingsComponent#Settings): Unit = {
      val inserterConfig = {
        val megs: Double = 1000 * 1000
        def mem(n: Int) = f"${n / megs}%.0fM"

        val res = settings.approximatedResources

        // TODO: allow for fine grained settings
        val relsPerNode = 3
        val propsPerNode = 4

        // as per http://docs.neo4j.org/chunked/stable/configuration-caches.html
        val bytesPerNode = 14
        val bytesPerRel = 33
        val bytesPerProp = 42
        val bytesPerStringProp = 128 // might be totally off

        val nodes = res
        val relationships = nodes * relsPerNode
        val properties = nodes * propsPerNode
        val stringProperties = properties

        val nodesMem = mem(nodes * bytesPerNode)
        val relsMem = mem(relationships * bytesPerRel)
        val propsMem = mem(properties * bytesPerProp)
        val stringPropsMem = mem(stringProperties * bytesPerStringProp)

        MapUtil.stringMap(
          // TODO: make cache_type configurable
          "cache_type", "none",
          "use_memory_mapped_buffers", "true",
          "neostore.nodestore.db.mapped_memory", nodesMem,
          "neostore.relationshipstore.db.mapped_memory", relsMem,
          "neostore.propertystore.db.mapped_memory", propsMem,
          "neostore.propertystore.db.strings.mapped_memory", stringPropsMem,
          "neostore.propertystore.db.arrays.mapped_memory", "0M",
          "neostore.propertystore.db.index.keys.mapped_memory", "5M",
          "neostore.propertystore.db.index.mapped_memory", "5M")
      }

      val batchInserter = {
        val config = inserterConfig
        BatchInserters.inserter(settings.graphDbDir, config)
      }

      if (settings.createDeferredIndices) {
        batchInserter.createDeferredSchemaIndex(DynamicLabel.label(Labels.resource)).on(Properties.uri).create()
        batchInserter.createDeferredSchemaIndex(DynamicLabel.label(Labels.literal)).on(Properties.value).create()
      }

      inserter = batchInserter

      resources = ObjectLongOpenHashMap.newInstanceWithExpectedSize(settings.approximatedResources)
      bnodes = ObjectLongOpenHashMap.newInstanceWithExpectedSize(settings.txSize)
    }

    private def getLabel(label: String): Label = {
      if (labels.containsKey(label)) labels.lget()
      else {
        val newLabel = DynamicLabel.label(label)
        labels.put(label, newLabel)
        newLabel
      }
    }

    private def makeLabels(dynamicLabels: List[String]): List[Label] = dynamicLabels.map(getLabel)

    private def get(cache: ObjectLongMap[String], key: String): Long = {
      cache.getOrDefault(key, -1)
    }

    private def set(cache: ObjectLongMap[String], key: String, properties: util.Map[String, AnyRef], labels: List[Label]): NodeType = {
      val n = inserter.createNode(properties, labels: _*)
      cache.put(key, n)
      n
    }

    private def props(k: String, v: AnyRef): util.Map[String, AnyRef] = {
      p1.put(k, v)
      p1
    }

    private def props(k1: String, v1: AnyRef, k2: String, v2: String): util.Map[String, AnyRef] = {
      p2.put(k1, v1)
      p2.put(k2, v2)
      p2
    }

    protected def getBNode(subject: String) = {
      val n = get(bnodes, subject)
      if (n == -1) None else Some(n)
    }

    protected def createBNode(subject: String, labels: List[String]) = {
      set(bnodes, subject, null, makeLabels(labels))
    }

    override def getOrCreateBNode(name: String, labels: List[String]): NodeType = {
      val n = get(bnodes, name)
      if (n == -1) timeCreateBNode(name, labels)
      else n
    }

    protected def getResourceNode(uri: String) = {
      val n = get(resources, uri)
      if (n == -1) None else Some(n)
    }

    protected def createResourceNode(uri: String, value: Option[String], labels: List[String]) = {
      val ls = makeLabels(labels)
      val p = value.fold(props(Properties.uri, uri)) { v ⇒
        props(Properties.uri, uri, Properties.value, v)
      }
      set(resources, uri, p, ls)
    }

    override def getOrCreateResource(uri: String, value: Option[String], labels: List[String]): NodeType = {
      val n = get(resources, uri)
      if (n == -1) timeCreateResource(uri, value, labels)
      else n
    }

    def updateResourceNode(id: Long, value: Option[String], labels: List[String]) = {

      val valuesUpdated = maybeUpdateValue(id, value)
      val labelsUpdated = maybeUpdateLabels(id, labels)

      if (valuesUpdated || labelsUpdated) {

        metrics.nodeUpdated()
      }

      id
    }

    override def createOrUpdateResource(uri: String, value: Option[String], labels: List[String]): NodeType = {
      val n = get(resources, uri)
      if (n != -1) timeUpdateResource(n, value, labels)
      else timeCreateResource(uri, value, labels)
    }

    private def maybeUpdateValue(id: Long, value: Option[String]) = {

      value.fold(false) { v ⇒
        val propsBefore = inserter.getNodeProperties(id)
        if (v != propsBefore.get(Properties.value)) {
          propsBefore.put(Properties.value, v)
          inserter.setNodeProperties(id, propsBefore)

          true
        }
        else false
      }
    }

    private def maybeUpdateLabels(id: Long, labels: List[String]) = {

      val newLabels = makeLabels(labels)
      val oldLabels = inserter.getNodeLabels(id)

      mergeLabels(oldLabels, newLabels).fold(false) { finalLabels ⇒
        inserter.setNodeLabels(id, finalLabels: _*)
        true
      }
    }

    private def mergeLabels(jLabels: java.lang.Iterable[Label], newLabels: List[Label]): Option[List[Label]] = {
      var oldLabels = Set.empty[Label]
      val builder = new ListBuffer[Label]
      val iter = jLabels.iterator()

      while (iter.hasNext) {
        val label = iter.next()
        builder += label
        oldLabels += label
      }

      @tailrec
      def loop0(ls: List[Label], hasNew: Boolean): Boolean =
        if (ls.isEmpty) hasNew
        else if (!oldLabels(ls.head)) {
          builder += ls.head
          loop0(ls.tail, hasNew = true)
        }
        else {
          loop0(ls.tail, hasNew)
        }

      val hasNewLabels = loop0(newLabels, hasNew = false)

      //      newLabels foreach { label ⇒
      //        if (!oldLabels(label)) {
      //          builder += label
      //          hasNewLabels = true
      //        }
      //      }

      if (hasNewLabels) Some(builder.result())
      else None
    }

    def createLiteralNode(literal: String, labels: List[String]) = {
      val p = props(Properties.value, literal)
      val ls = makeLabels(Labels.literal :: labels)
      val n = inserter.createNode(p, ls: _*)
      n
    }

    def createRelationship(src: Long, dest: Long, rel: String, relType: RelationshipType): Unit = {
      val p = props(Properties.uri, rel)
      inserter.createRelationship(src, dest, relType, p)
    }

    def createRelTypeFor(name: String) = DynamicRelationshipType.withName(name)

    def subjectAdded() = ()

    def shutdown() = {
      inserter.shutdown()
    }
  }

}
