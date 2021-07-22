package marquez.service;

import com.google.common.base.Functions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import graphql.schema.DataFetchingFieldSelectionSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import marquez.common.models.DatasetId;
import marquez.common.models.JobId;
import marquez.db.LineageDao;
import marquez.db.models.DatasetData;
import marquez.db.models.JobData;
import marquez.service.DelegatingDaos.DelegatingLineageDao;
import marquez.service.models.Edge;
import marquez.service.models.Graph;
import marquez.service.models.Lineage;
import marquez.service.models.Node;
import marquez.service.models.NodeId;
import marquez.service.models.NodeType;
import marquez.service.models.Run;

@Slf4j
public class LineageService extends DelegatingLineageDao {
  public LineageService(LineageDao delegate) {
    super(delegate);
  }

  public Lineage lineage(NodeId nodeId, int depth) {
    return lineage(nodeId, depth, null);
  }

  public Lineage lineage(NodeId nodeId, int depth, DataFetchingFieldSelectionSet lineageFields) {
    Optional<UUID> optionalUUID = getJobUuid(nodeId);
    if (optionalUUID.isEmpty()) {
      throw new NodeIdNotFoundException("Could not find node");
    }
    UUID job = optionalUUID.get();

    Stopwatch sw = Stopwatch.createStarted();
    DataFetchingFieldSelectionSet dataSelectionSet =
        lineageFields == null
            ? null
            : lineageFields.getFields("graph/data").get(0).getSelectionSet();
    Set<JobData> jobData = getLineage(job, depth, dataSelectionSet);
    sw.stop();
    log.info("Elapsed {} millis for getLineage", sw.elapsed().toMillis());

    sw.reset();
    sw.start();
    DataFetchingFieldSelectionSet runSelectionSet = null;
    if (lineageFields != null && lineageFields.contains("graph/data/latestRun")) {
      runSelectionSet = lineageFields.getFields("graph/data/latestRun").get(0).getSelectionSet();
    }
    List<Run> runs;
    if (runSelectionSet == null) {
      runs = getCurrentRuns(jobData.stream().map(JobData::getUuid).collect(Collectors.toSet()));
    } else {
      runs =
          getCurrentRuns(
              jobData.stream().map(JobData::getUuid).collect(Collectors.toSet()), runSelectionSet);
    }
    sw.stop();
    log.info("Elapsed {} millis for getCurrentRuns", sw.elapsed().toMillis());
    sw.reset();
    // todo fix runtime
    for (JobData j : jobData) {
      if (j.getLatestRun().isEmpty()) {
        for (Run run : runs) {
          if (j.getName().getValue().equalsIgnoreCase(run.getJobName())
              && j.getNamespace().getValue().equalsIgnoreCase(run.getNamespaceName())) {
            j.setLatestRun(run);
            break;
          }
        }
      }
    }
    sw.start();
    Set<UUID> datasetIds =
        jobData.stream()
            .flatMap(jd -> Stream.concat(jd.getInputUuids().stream(), jd.getOutputUuids().stream()))
            .collect(Collectors.toSet());
    Set<DatasetData> datasets = new HashSet<>();
    if (!datasetIds.isEmpty()) {
      datasets.addAll(getDatasetData(datasetIds));
    }
    sw.stop();
    log.info("Elapsed {} millis for getDatasetData", sw.elapsed().toMillis());

    return toLineage(jobData, datasets);
  }

  private Lineage toLineage(Set<JobData> jobData, Set<DatasetData> datasets) {
    Set<Node> nodes = new LinkedHashSet<>();
    // build mapping for later
    Map<UUID, DatasetData> datasetById =
        datasets.stream().collect(Collectors.toMap(DatasetData::getUuid, Functions.identity()));

    Map<DatasetData, Set<UUID>> dsInputToJob = new HashMap<>();
    Map<DatasetData, Set<UUID>> dsOutputToJob = new HashMap<>();
    // build jobs
    Map<UUID, JobData> jobDataMap = Maps.uniqueIndex(jobData, JobData::getUuid);
    for (JobData data : jobData) {
      if (data == null) {
        log.error("Could not find job node for {}", jobData);
        continue;
      }
      Set<DatasetData> inputs =
          data.getInputUuids().stream().map(datasetById::get).collect(Collectors.toSet());
      Set<DatasetData> outputs =
          data.getOutputUuids().stream().map(datasetById::get).collect(Collectors.toSet());
      data.setInputs(buildDatasetId(inputs));
      data.setOutputs(buildDatasetId(outputs));

      inputs.forEach(
          ds -> dsInputToJob.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));
      outputs.forEach(
          ds -> dsOutputToJob.computeIfAbsent(ds, e -> new HashSet<>()).add(data.getUuid()));

      NodeId origin = NodeId.of(new JobId(data.getNamespace(), data.getName()));
      Node node =
          new Node(
              origin,
              NodeType.JOB,
              data,
              buildDatasetEdge(inputs, origin),
              buildDatasetEdge(origin, outputs));
      nodes.add(node);
    }

    for (DatasetData dataset : datasets) {
      NodeId origin = NodeId.of(new DatasetId(dataset.getNamespace(), dataset.getName()));
      Node node =
          new Node(
              origin,
              NodeType.DATASET,
              dataset,
              buildJobEdge(dsOutputToJob.get(dataset), origin, jobDataMap),
              buildJobEdge(origin, dsInputToJob.get(dataset), jobDataMap));
      nodes.add(node);
    }

    return new Lineage(Lineage.withSortedNodes(Graph.directed().nodes(nodes).build()));
  }

  private ImmutableSet<DatasetId> buildDatasetId(Set<DatasetData> datasetData) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new DatasetId(ds.getNamespace(), ds.getName()))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildJobEdge(
      NodeId origin, Set<UUID> uuids, Map<UUID, JobData> jobDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(jobDataMap::get)
        .filter(Objects::nonNull)
        .map(j -> new Edge(origin, buildEdge(j)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildJobEdge(
      Set<UUID> uuids, NodeId origin, Map<UUID, JobData> jobDataMap) {
    if (uuids == null) {
      return ImmutableSet.of();
    }
    return uuids.stream()
        .map(jobDataMap::get)
        .filter(Objects::nonNull)
        .map(j -> new Edge(buildEdge(j), origin))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetEdge(NodeId nodeId, Set<DatasetData> datasetData) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new Edge(nodeId, buildEdge(ds)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private ImmutableSet<Edge> buildDatasetEdge(Set<DatasetData> datasetData, NodeId nodeId) {
    if (datasetData == null) {
      return ImmutableSet.of();
    }
    return datasetData.stream()
        .map(ds -> new Edge(buildEdge(ds), nodeId))
        .collect(ImmutableSet.toImmutableSet());
  }

  private NodeId buildEdge(DatasetData ds) {
    return NodeId.of(new DatasetId(ds.getNamespace(), ds.getName()));
  }

  private NodeId buildEdge(JobData e) {
    return NodeId.of(new JobId(e.getNamespace(), e.getName()));
  }

  public Optional<UUID> getJobUuid(NodeId nodeId) {
    if (nodeId.isJobType()) {
      JobId jobId = nodeId.asJobId();
      return getJobUuid(jobId.getName().getValue(), jobId.getNamespace().getValue());
    } else if (nodeId.isDatasetType()) {
      DatasetId datasetId = nodeId.asDatasetId();
      return getJobFromInputOrOutput(
          datasetId.getName().getValue(), datasetId.getNamespace().getValue());
    } else {
      throw new NodeIdNotFoundException("Node must be a dataset node or job node");
    }
  }
}
