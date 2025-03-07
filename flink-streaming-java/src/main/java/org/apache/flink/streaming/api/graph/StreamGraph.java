/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.InputTypeConfigurable;
import org.apache.flink.api.java.typeutils.MissingTypeInfo;
import org.apache.flink.optimizer.plan.StreamingPlan;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.OutputTypeConfigurable;
import org.apache.flink.streaming.api.operators.StoppableStreamSource;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RebalancePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.tasks.OneInputStreamTask;
import org.apache.flink.streaming.runtime.tasks.SourceStreamTask;
import org.apache.flink.streaming.runtime.tasks.StoppableSourceStreamTask;
import org.apache.flink.streaming.runtime.tasks.StreamIterationHead;
import org.apache.flink.streaming.runtime.tasks.StreamIterationTail;
import org.apache.flink.streaming.runtime.tasks.TwoInputStreamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class representing the streaming topology. It contains all the information
 * necessary to build the jobgraph for the execution.
 * 
 */
@Internal
public class StreamGraph extends StreamingPlan {
	
	private static final Logger LOG = LoggerFactory.getLogger(StreamGraph.class);

	private String jobName = StreamExecutionEnvironment.DEFAULT_JOB_NAME;

	private final StreamExecutionEnvironment environemnt;
	private final ExecutionConfig executionConfig;
	private final CheckpointConfig checkpointConfig;
	
	private boolean chaining;

	private Map<Integer, StreamNode> streamNodes;
	private Set<Integer> sources;
	private Set<Integer> sinks;
	private Map<Integer, Tuple2<Integer, List<String>>> virtualSelectNodes;
	private Map<Integer, Tuple2<Integer, StreamPartitioner<?>>> virtuaPartitionNodes;

	protected Map<Integer, String> vertexIDtoBrokerID;
	protected Map<Integer, Long> vertexIDtoLoopTimeout;
	private AbstractStateBackend stateBackend;
	private Set<Tuple2<StreamNode, StreamNode>> iterationSourceSinkPairs;


	public StreamGraph(StreamExecutionEnvironment environment) {
		this.environemnt = environment;
		this.executionConfig = environment.getConfig();
		this.checkpointConfig = environment.getCheckpointConfig();

		// create an empty new stream graph.
		clear();
	}

	/**
	 * Remove all registered nodes etc.
	 */
	public void clear() {
		streamNodes = new HashMap<>();
		virtualSelectNodes = new HashMap<>();
		virtuaPartitionNodes = new HashMap<>();
		vertexIDtoBrokerID = new HashMap<>();
		vertexIDtoLoopTimeout  = new HashMap<>();
		iterationSourceSinkPairs = new HashSet<>();
		sources = new HashSet<>();
		sinks = new HashSet<>();
	}

	public ExecutionConfig getExecutionConfig() {
		return executionConfig;
	}
	
	public CheckpointConfig getCheckpointConfig() {
		return checkpointConfig;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public void setChaining(boolean chaining) {
		this.chaining = chaining;
	}

	public void setStateBackend(AbstractStateBackend backend) {
		this.stateBackend = backend;
	}

	public AbstractStateBackend getStateBackend() {
		return this.stateBackend;
	}

	// Checkpointing
	
	public boolean isChainingEnabled() {
		return chaining;
	}
	

	public boolean isIterative() {
		return!vertexIDtoLoopTimeout.isEmpty();
	}

	public <IN, OUT> void addSource(Integer vertexID, StreamOperator<OUT> operatorObject,
			TypeInformation<IN> inTypeInfo, TypeInformation<OUT> outTypeInfo, String operatorName) {
		addOperator(vertexID, operatorObject, inTypeInfo, outTypeInfo, operatorName);
		sources.add(vertexID);
	}

	public <IN, OUT> void addSink(Integer vertexID, StreamOperator<OUT> operatorObject,
			TypeInformation<IN> inTypeInfo, TypeInformation<OUT> outTypeInfo, String operatorName) {
		addOperator(vertexID, operatorObject, inTypeInfo, outTypeInfo, operatorName);
		sinks.add(vertexID);
	}

	public <IN, OUT> void addOperator(
			Integer vertexID,
			StreamOperator<OUT> operatorObject,
			TypeInformation<IN> inTypeInfo,
			TypeInformation<OUT> outTypeInfo,
			String operatorName) {

		if (operatorObject instanceof StoppableStreamSource) {
			addNode(vertexID, StoppableSourceStreamTask.class, operatorObject, operatorName);
		} else if (operatorObject instanceof StreamSource) {
			addNode(vertexID, SourceStreamTask.class, operatorObject, operatorName);
		} else {
			addNode(vertexID, OneInputStreamTask.class, operatorObject, operatorName);
		}

		TypeSerializer<IN> inSerializer = inTypeInfo != null && !(inTypeInfo instanceof MissingTypeInfo) ? inTypeInfo.createSerializer(executionConfig) : null;

		TypeSerializer<OUT> outSerializer = outTypeInfo != null && !(outTypeInfo instanceof MissingTypeInfo) ? outTypeInfo.createSerializer(executionConfig) : null;

		setSerializers(vertexID, inSerializer, null, outSerializer);

		if (operatorObject instanceof OutputTypeConfigurable) {
			@SuppressWarnings("unchecked")
			OutputTypeConfigurable<OUT> outputTypeConfigurable = (OutputTypeConfigurable<OUT>) operatorObject;
			// sets the output type which must be know at StreamGraph creation time
			outputTypeConfigurable.setOutputType(outTypeInfo, executionConfig);
		}

		if (operatorObject instanceof InputTypeConfigurable) {
			InputTypeConfigurable inputTypeConfigurable = (InputTypeConfigurable) operatorObject;
			inputTypeConfigurable.setInputType(inTypeInfo, executionConfig);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Vertex: {}", vertexID);
		}
	}

	public <IN1, IN2, OUT> void addCoOperator(
			Integer vertexID,
			TwoInputStreamOperator<IN1, IN2, OUT> taskOperatorObject,
			TypeInformation<IN1> in1TypeInfo,
			TypeInformation<IN2> in2TypeInfo,
			TypeInformation<OUT> outTypeInfo,
			String operatorName) {

		addNode(vertexID, TwoInputStreamTask.class, taskOperatorObject, operatorName);

		TypeSerializer<OUT> outSerializer = (outTypeInfo != null) && !(outTypeInfo instanceof MissingTypeInfo) ?
				outTypeInfo.createSerializer(executionConfig) : null;

		setSerializers(vertexID, in1TypeInfo.createSerializer(executionConfig), in2TypeInfo.createSerializer(executionConfig), outSerializer);

		if (taskOperatorObject instanceof OutputTypeConfigurable) {
			@SuppressWarnings("unchecked")
			OutputTypeConfigurable<OUT> outputTypeConfigurable = (OutputTypeConfigurable<OUT>) taskOperatorObject;
			// sets the output type which must be know at StreamGraph creation time
			outputTypeConfigurable.setOutputType(outTypeInfo, executionConfig);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("CO-TASK: {}", vertexID);
		}
	}

	protected StreamNode addNode(Integer vertexID, Class<? extends AbstractInvokable> vertexClass,
			StreamOperator<?> operatorObject, String operatorName) {

		if (streamNodes.containsKey(vertexID)) {
			throw new RuntimeException("Duplicate vertexID " + vertexID);
		}

		StreamNode vertex = new StreamNode(environemnt, vertexID, operatorObject, operatorName,
				new ArrayList<OutputSelector<?>>(), vertexClass);

		streamNodes.put(vertexID, vertex);

		return vertex;
	}

	/**
	 * Adds a new virtual node that is used to connect a downstream vertex to only the outputs
	 * with the selected names.
	 *
	 * When adding an edge from the virtual node to a downstream node the connection will be made
	 * to the original node, only with the selected names given here.
	 *
	 * @param originalId ID of the node that should be connected to.
	 * @param virtualId ID of the virtual node.
	 * @param selectedNames The selected names.
	 */
	public void addVirtualSelectNode(Integer originalId, Integer virtualId, List<String> selectedNames) {

		if (virtualSelectNodes.containsKey(virtualId)) {
			throw new IllegalStateException("Already has virtual select node with id " + virtualId);
		}

		virtualSelectNodes.put(virtualId,
				new Tuple2<Integer, List<String>>(originalId, selectedNames));
	}

	/**
	 * Adds a new virtual node that is used to connect a downstream vertex to an input with a certain
	 * partitioning.
	 *
	 * When adding an edge from the virtual node to a downstream node the connection will be made
	 * to the original node, but with the partitioning given here.
	 *
	 * @param originalId ID of the node that should be connected to.
	 * @param virtualId ID of the virtual node.
	 * @param partitioner The partitioner
	 */
	public void addVirtualPartitionNode(Integer originalId, Integer virtualId, StreamPartitioner<?> partitioner) {

		if (virtuaPartitionNodes.containsKey(virtualId)) {
			throw new IllegalStateException("Already has virtual partition node with id " + virtualId);
		}

		virtuaPartitionNodes.put(virtualId,
				new Tuple2<Integer, StreamPartitioner<?>>(originalId, partitioner));
	}

	public void addEdge(Integer upStreamVertexID, Integer downStreamVertexID, int typeNumber) {
		addEdgeInternal(upStreamVertexID,
				downStreamVertexID,
				typeNumber,
				null,
				new ArrayList<String>());

	}

	private void addEdgeInternal(Integer upStreamVertexID,
			Integer downStreamVertexID,
			int typeNumber,
			StreamPartitioner<?> partitioner,
			List<String> outputNames) {


		if (virtualSelectNodes.containsKey(upStreamVertexID)) {
			int virtualId = upStreamVertexID;
			upStreamVertexID = virtualSelectNodes.get(virtualId).f0;
			if (outputNames.isEmpty()) {
				// selections that happen downstream override earlier selections
				outputNames = virtualSelectNodes.get(virtualId).f1;
			}
			addEdgeInternal(upStreamVertexID, downStreamVertexID, typeNumber, partitioner, outputNames);
		} else if (virtuaPartitionNodes.containsKey(upStreamVertexID)) {
			int virtualId = upStreamVertexID;
			upStreamVertexID = virtuaPartitionNodes.get(virtualId).f0;
			if (partitioner == null) {
				partitioner = virtuaPartitionNodes.get(virtualId).f1;
			}
			addEdgeInternal(upStreamVertexID, downStreamVertexID, typeNumber, partitioner, outputNames);
		} else {
			StreamNode upstreamNode = getStreamNode(upStreamVertexID);
			StreamNode downstreamNode = getStreamNode(downStreamVertexID);

			// If no partitioner was specified and the parallelism of upstream and downstream
			// operator matches use forward partitioning, use rebalance otherwise.
			if (partitioner == null && upstreamNode.getParallelism() == downstreamNode.getParallelism()) {
				partitioner = new ForwardPartitioner<Object>();
			} else if (partitioner == null) {
				partitioner = new RebalancePartitioner<Object>();
			}

			if (partitioner instanceof ForwardPartitioner) {
				if (upstreamNode.getParallelism() != downstreamNode.getParallelism()) {
					throw new UnsupportedOperationException("Forward partitioning does not allow " +
							"change of parallelism. Upstream operation: " + upstreamNode + " parallelism: " + upstreamNode.getParallelism() +
							", downstream operation: " + downstreamNode + " parallelism: " + downstreamNode.getParallelism() +
							" You must use another partitioning strategy, such as broadcast, rebalance, shuffle or global.");
				}
			}

			StreamEdge edge = new StreamEdge(upstreamNode, downstreamNode, typeNumber, outputNames, partitioner);

			getStreamNode(edge.getSourceId()).addOutEdge(edge);
			getStreamNode(edge.getTargetId()).addInEdge(edge);
		}
	}

	public <T> void addOutputSelector(Integer vertexID, OutputSelector<T> outputSelector) {
		if (virtuaPartitionNodes.containsKey(vertexID)) {
			addOutputSelector(virtuaPartitionNodes.get(vertexID).f0, outputSelector);
		} else if (virtualSelectNodes.containsKey(vertexID)) {
			addOutputSelector(virtualSelectNodes.get(vertexID).f0, outputSelector);
		} else {
			getStreamNode(vertexID).addOutputSelector(outputSelector);

			if (LOG.isDebugEnabled()) {
				LOG.debug("Outputselector set for {}", vertexID);
			}
		}

	}

	public void setParallelism(Integer vertexID, int parallelism) {
		if (getStreamNode(vertexID) != null) {
			getStreamNode(vertexID).setParallelism(parallelism);
		}
	}

	public void setOneInputStateKey(Integer vertexID, KeySelector<?, ?> keySelector, TypeSerializer<?> keySerializer) {
		StreamNode node = getStreamNode(vertexID);
		node.setStatePartitioner1(keySelector);
		node.setStateKeySerializer(keySerializer);
	}

	public void setTwoInputStateKey(Integer vertexID, KeySelector<?, ?> keySelector1, KeySelector<?, ?> keySelector2, TypeSerializer<?> keySerializer) {
		StreamNode node = getStreamNode(vertexID);
		node.setStatePartitioner1(keySelector1);
		node.setStatePartitioner2(keySelector2);
		node.setStateKeySerializer(keySerializer);
	}

	public void setBufferTimeout(Integer vertexID, long bufferTimeout) {
		if (getStreamNode(vertexID) != null) {
			getStreamNode(vertexID).setBufferTimeout(bufferTimeout);
		}
	}

	public void setSerializers(Integer vertexID, TypeSerializer<?> in1, TypeSerializer<?> in2, TypeSerializer<?> out) {
		StreamNode vertex = getStreamNode(vertexID);
		vertex.setSerializerIn1(in1);
		vertex.setSerializerIn2(in2);
		vertex.setSerializerOut(out);
	}

	public void setSerializersFrom(Integer from, Integer to) {
		StreamNode fromVertex = getStreamNode(from);
		StreamNode toVertex = getStreamNode(to);

		toVertex.setSerializerIn1(fromVertex.getTypeSerializerOut());
		toVertex.setSerializerOut(fromVertex.getTypeSerializerIn1());
	}

	public <OUT> void setOutType(Integer vertexID, TypeInformation<OUT> outType) {
		getStreamNode(vertexID).setSerializerOut(outType.createSerializer(executionConfig));
	}

	public <IN, OUT> void setOperator(Integer vertexID, StreamOperator<OUT> operatorObject) {
		getStreamNode(vertexID).setOperator(operatorObject);
	}

	public void setInputFormat(Integer vertexID, InputFormat<?, ?> inputFormat) {
		getStreamNode(vertexID).setInputFormat(inputFormat);
	}

	public void setResourceStrategy(Integer vertexID, ResourceStrategy strategy) {
		StreamNode node = getStreamNode(vertexID);
		if (node == null) {
			return;
		}

		switch (strategy) {
		case ISOLATE:
			node.isolateSlot();
			break;
		case NEWGROUP:
			node.startNewSlotSharingGroup();
			break;
		default:
			throw new IllegalArgumentException("Unknown resource strategy");
		}
	}

	void setTransformationId(Integer nodeId, String transformationId) {
		StreamNode node = streamNodes.get(nodeId);
		if (node != null) {
			node.setTransformationId(transformationId);
		}
	}

	public StreamNode getStreamNode(Integer vertexID) {
		return streamNodes.get(vertexID);
	}

	protected Collection<? extends Integer> getVertexIDs() {
		return streamNodes.keySet();
	}

	public List<StreamEdge> getStreamEdges(int sourceId, int targetId) {

		List<StreamEdge> result = new ArrayList<>();
		for (StreamEdge edge : getStreamNode(sourceId).getOutEdges()) {
			if (edge.getTargetId() == targetId) {
				result.add(edge);
			}
		}

		if (result.isEmpty()) {
			throw new RuntimeException("No such edge in stream graph: " + sourceId + " -> " + targetId);
		}

		return result;
	}

	public Collection<Integer> getSourceIDs() {
		return sources;
	}


	public Collection<Integer> getSinkIDs() {
		return sinks;
	}

	public Collection<StreamNode> getStreamNodes() {
		return streamNodes.values();
	}

	public Set<Tuple2<Integer, StreamOperator<?>>> getOperators() {
		Set<Tuple2<Integer, StreamOperator<?>>> operatorSet = new HashSet<>();
		for (StreamNode vertex : streamNodes.values()) {
			operatorSet.add(new Tuple2<Integer, StreamOperator<?>>(vertex.getId(), vertex
					.getOperator()));
		}
		return operatorSet;
	}

	public String getBrokerID(Integer vertexID) {
		return vertexIDtoBrokerID.get(vertexID);
	}

	public long getLoopTimeout(Integer vertexID) {
		return vertexIDtoLoopTimeout.get(vertexID);
	}

	public Tuple2<StreamNode, StreamNode> createIterationSourceAndSink(int loopId, int sourceId, int sinkId, long timeout, int parallelism) {
		StreamNode source = this.addNode(sourceId,
				StreamIterationHead.class,
				null,
				null);
		sources.add(source.getId());
		setParallelism(source.getId(), parallelism);

		StreamNode sink = this.addNode(sinkId,
				StreamIterationTail.class,
				null,
				null);
		sinks.add(sink.getId());
		setParallelism(sink.getId(), parallelism);

		iterationSourceSinkPairs.add(new Tuple2<>(source, sink));

		source.setOperatorName("IterationSource-" + loopId);
		sink.setOperatorName("IterationSink-" + loopId);
		this.vertexIDtoBrokerID.put(source.getId(), "broker-" + loopId);
		this.vertexIDtoBrokerID.put(sink.getId(), "broker-" + loopId);
		this.vertexIDtoLoopTimeout.put(source.getId(), timeout);
		this.vertexIDtoLoopTimeout.put(sink.getId(), timeout);

		return new Tuple2<>(source, sink);
	}

	public Set<Tuple2<StreamNode, StreamNode>> getIterationSourceSinkPairs() {
		return iterationSourceSinkPairs;
	}

	private void removeEdge(StreamEdge edge) {
		edge.getSourceVertex().getOutEdges().remove(edge);
		edge.getTargetVertex().getInEdges().remove(edge);
	}

	private void removeVertex(StreamNode toRemove) {
		Set<StreamEdge> edgesToRemove = new HashSet<>();

		edgesToRemove.addAll(toRemove.getInEdges());
		edgesToRemove.addAll(toRemove.getOutEdges());

		for (StreamEdge edge : edgesToRemove) {
			removeEdge(edge);
		}
		streamNodes.remove(toRemove.getId());
	}

	/**
	 * Gets the assembled {@link JobGraph}.
	 */
	@SuppressWarnings("deprecation")
	public JobGraph getJobGraph() {
		// temporarily forbid checkpointing for iterative jobs
		if (isIterative() && checkpointConfig.isCheckpointingEnabled() && !checkpointConfig.isForceCheckpointing()) {
			throw new UnsupportedOperationException(
					"Checkpointing is currently not supported by default for iterative jobs, as we cannot guarantee exactly once semantics. "
							+ "State checkpoints happen normally, but records in-transit during the snapshot will be lost upon failure. "
							+ "\nThe user can force enable state checkpoints with the reduced guarantees by calling: env.enableCheckpointing(interval,true)");
		}

		StreamingJobGraphGenerator jobgraphGenerator = new StreamingJobGraphGenerator(this);

		return jobgraphGenerator.createJobGraph(jobName);
	}

	@Override
	public String getStreamingPlanAsJSON() {
		try {
			return new JSONGenerator(this).getJSON();
		}
		catch (Exception e) {
			throw new RuntimeException("JSON plan creation failed", e);
		}
	}

	@Override
	public void dumpStreamingPlanAsJSON(File file) throws IOException {
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(new FileOutputStream(file), false);
			pw.write(getStreamingPlanAsJSON());
			pw.flush();

		} finally {
			if (pw != null) {
				pw.close();
			}
		}
	}

	public static enum ResourceStrategy {
		DEFAULT, ISOLATE, NEWGROUP
	}
}
