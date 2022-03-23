package datahub.protobuf.model;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import datahub.protobuf.ProtobufUtils;
import datahub.protobuf.visitors.ProtobufModelVisitor;
import datahub.protobuf.visitors.VisitContext;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class ProtobufGraph extends DefaultDirectedGraph<ProtobufElement, FieldTypeEdge> {
    private final transient ProtobufMessage rootProtobufMessage;
    private final transient AllDirectedPaths<ProtobufElement, FieldTypeEdge> directedPaths;
    private final transient ExtensionRegistry registry;

    public ProtobufGraph(DescriptorProtos.FileDescriptorSet fileSet) throws InvalidProtocolBufferException {
        this(fileSet, null, null, true);
    }

    public ProtobufGraph(DescriptorProtos.FileDescriptorSet fileSet, String messageName) throws InvalidProtocolBufferException {
        this(fileSet, messageName, null, true);
    }

    public ProtobufGraph(DescriptorProtos.FileDescriptorSet fileSet, String messageName, String relativeFilename) throws InvalidProtocolBufferException {
        this(fileSet, messageName, relativeFilename, true);
    }

    public ProtobufGraph(DescriptorProtos.FileDescriptorSet fileSet, String messageName, String filename,
                         boolean flattenGoogleWrapped) throws InvalidProtocolBufferException {
        super(FieldTypeEdge.class);
        this.registry = ProtobufUtils.buildRegistry(fileSet);
        DescriptorProtos.FileDescriptorSet fileSetExtended = DescriptorProtos.FileDescriptorSet
                .parseFrom(fileSet.toByteArray(), this.registry);
        buildProtobufGraph(fileSetExtended);
        if (flattenGoogleWrapped) {
            flattenGoogleWrapped();
        }

        if (messageName != null) {
            this.rootProtobufMessage = findMessage(messageName);
        } else {
            DescriptorProtos.FileDescriptorProto lastFile = fileSetExtended.getFileList()
                    .stream().filter(f -> filename != null && filename.endsWith(f.getName()))
                    .findFirst().orElse(fileSetExtended.getFile(fileSetExtended.getFileCount() - 1));
            this.rootProtobufMessage = autodetectRootMessage(lastFile);
        }

        this.directedPaths = new AllDirectedPaths<>(this);
    }

    public List<GraphPath<ProtobufElement, FieldTypeEdge>> getAllPaths(ProtobufElement a, ProtobufElement b) {
        return directedPaths.getAllPaths(a, b, true, null);
    }

    public ExtensionRegistry getRegistry() {
        return registry;
    }

    public String getFullName() {
        return rootProtobufMessage.fullName();
    }

    public int getMajorVersion() {
        return rootProtobufMessage.majorVersion();
    }

    public String getComment() {
        return rootProtobufMessage.comment();
    }

    public ProtobufMessage root() {
        return rootProtobufMessage;
    }


    public <T, V extends ProtobufModelVisitor<T>> Stream<T> accept(VisitContext.VisitContextBuilder contextBuilder, Collection<V> visitors) {
        VisitContext context = Optional.ofNullable(contextBuilder).orElse(VisitContext.builder()).graph(this).build();
        return accept(context, visitors);
    }

    public <T, V extends ProtobufModelVisitor<T>> Stream<T> accept(VisitContext context, Collection<V> visitors) {
        return Stream.concat(
                visitors.stream().flatMap(visitor -> visitor.visitGraph(context)),
                vertexSet().stream().flatMap(vertex -> visitors.stream().flatMap(visitor -> vertex.accept(visitor, context)))
        );
    }

    protected ProtobufMessage autodetectRootMessage(DescriptorProtos.FileDescriptorProto lastFile) throws IllegalArgumentException {
        return (ProtobufMessage) vertexSet().stream()
                .filter(v -> // incoming edges of fields
                        lastFile.equals(v.fileProto())
                                && v instanceof ProtobufMessage
                                && incomingEdgesOf(v).isEmpty()
                                && outgoingEdgesOf(v).stream()
                                .flatMap(e -> incomingEdgesOf(e.getEdgeTarget()).stream())
                                .allMatch(e -> e.getEdgeSource().equals(v))) // all the incoming edges on the child vertices should be self
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Cannot autodetect protobuf Message.")
                );
    }

    public ProtobufMessage findMessage(String messageName) throws IllegalArgumentException {
        return (ProtobufMessage) vertexSet().stream()
                .filter(v -> v instanceof ProtobufMessage && messageName.equals(v.fullName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find protobuf Message %s", messageName)));
    }

    private void buildProtobufGraph(DescriptorProtos.FileDescriptorSet fileSet) {
        // Attach fields to messages
        Map<String, List<ProtobufField>> messageFieldMap = fileSet.getFileList().stream().flatMap(fileProto ->
                fileProto.getMessageTypeList().stream().flatMap(messageProto -> {

                    ProtobufMessage messageVertex = ProtobufMessage.builder()
                            .fileProto(fileProto)
                            .messageProto(messageProto)
                            .build();
                    addVertex(messageVertex);

                    // Handle nested fields
                    Stream<ProtobufField> nestedFields = addNestedMessage(fileProto, messageProto);

                    // Add enum types
                    addEnum(fileProto, messageProto);

                    // handle normal fields and oneofs
                    Stream<ProtobufField> fields = messageProto.getFieldList().stream().flatMap(fieldProto -> {
                        ProtobufField fieldVertex = ProtobufField.builder()
                                .protobufMessage(messageVertex)
                                .fieldProto(fieldProto)
                                .build();

                        // Add field vertex
                        addVertex(fieldVertex);

                        if (fieldVertex.oneOfProto() != null) {
                            // Handle oneOf
                            return addOneOf(messageVertex, fieldVertex);
                        } else {
                            // Add schema to field edge
                            return linkMessageToField(messageVertex, fieldVertex);
                        }
                    });

                    return Stream.concat(nestedFields, fields);
                })
        ).collect(Collectors.groupingBy(ProtobufField::parentMessageName));

        attachMessagesToFields(messageFieldMap);
    }

    private void attachMessagesToFields(Map<String, List<ProtobufField>> messageFieldMap) {
        // Connect field to Message
        List<FieldTypeEdge> messageFieldEdges = edgeSet().stream()
                .filter(FieldTypeEdge::isMessageType)
                .collect(Collectors.toList());

        messageFieldEdges.forEach(e -> {
            ProtobufField source = (ProtobufField) e.getEdgeTarget();

            List<ProtobufField> targetFields = messageFieldMap.get(source.nativeType());
            if (targetFields != null) {
                targetFields.forEach(target ->
                        FieldTypeEdge.builder()
                                .edgeSource(source)
                                .edgeTarget(target)
                                .type(target.fieldPathType())
                                .isMessageType(target.isMessage())
                                .build().inGraph(this)
                );
            }
        });
    }

    private void addEnum(DescriptorProtos.FileDescriptorProto fileProto, DescriptorProtos.DescriptorProto messageProto) {
        messageProto.getEnumTypeList().forEach(enumProto -> {
            ProtobufEnum enumVertex = ProtobufEnum.enumBuilder()
                    .fileProto(fileProto)
                    .messageProto(messageProto)
                    .enumProto(enumProto)
                    .build();
            addVertex(enumVertex);
        });
    }

    private Stream<ProtobufField> addNestedMessage(DescriptorProtos.FileDescriptorProto fileProto, DescriptorProtos.DescriptorProto messageProto) {
        return messageProto.getNestedTypeList().stream().flatMap(nestedMessageProto -> {
            ProtobufMessage nestedMessageVertex = ProtobufMessage.builder()
                    .fileProto(fileProto)
                    .parentMessageProto(messageProto)
                    .messageProto(nestedMessageProto)
                    .build();
            addVertex(nestedMessageVertex);

            return nestedMessageProto.getFieldList().stream().map(nestedFieldProto -> {
                ProtobufField field = ProtobufField.builder()
                        .protobufMessage(nestedMessageVertex)
                        .fieldProto(nestedFieldProto)
                        .build();

                // Add field vertex
                addVertex(field);

                // Add schema to field edge
                if (!field.isMessage()) {
                    FieldTypeEdge.builder()
                            .edgeSource(nestedMessageVertex)
                            .edgeTarget(field)
                            .type(field.fieldPathType())
                            .build().inGraph(this);
                }

                return field;
            });
        });
    }

    private Stream<ProtobufField> addOneOf(ProtobufMessage messageVertex, ProtobufField fieldVertex) {
        // Handle oneOf
        ProtobufField oneOfVertex = ProtobufOneOfField.oneOfBuilder()
                .protobufMessage(messageVertex)
                .fieldProto(fieldVertex.getFieldProto())
                .build();
        addVertex(oneOfVertex);

        FieldTypeEdge.builder()
                .edgeSource(messageVertex)
                .edgeTarget(oneOfVertex)
                .type(oneOfVertex.fieldPathType())
                .build().inGraph(this);

        // Add oneOf field to field edge
        FieldTypeEdge.builder()
                .edgeSource(oneOfVertex)
                .edgeTarget(fieldVertex)
                .type(fieldVertex.fieldPathType())
                .isMessageType(fieldVertex.isMessage())
                .build().inGraph(this);

        return Stream.of(oneOfVertex);
    }

    private Stream<ProtobufField> linkMessageToField(ProtobufMessage messageVertex, ProtobufField fieldVertex) {
        FieldTypeEdge.builder()
                .edgeSource(messageVertex)
                .edgeTarget(fieldVertex)
                .type(fieldVertex.fieldPathType())
                .isMessageType(fieldVertex.isMessage())
                .build().inGraph(this);

        return Stream.of(fieldVertex);
    }

    private void flattenGoogleWrapped() {
        HashSet<ProtobufElement> removeVertices = new HashSet<>();
        HashSet<FieldTypeEdge> removeEdges = new HashSet<>();
        HashSet<ProtobufElement> addVertices = new HashSet<>();
        HashSet<FieldTypeEdge> addEdges = new HashSet<>();

        Set<ProtobufElement> googleWrapped = vertexSet().stream()
                .filter(v -> v instanceof ProtobufMessage
                        && "google/protobuf/wrappers.proto".equals(v.fileProto().getName()))
                .collect(Collectors.toSet());
        removeVertices.addAll(googleWrapped);

        Set<ProtobufField> wrappedPrimitiveFields = googleWrapped.stream()
                .flatMap(wrapped -> outgoingEdgesOf(wrapped).stream())
                .map(FieldTypeEdge::getEdgeTarget)
                .map(ProtobufField.class::cast)
                .collect(Collectors.toSet());
        removeVertices.addAll(wrappedPrimitiveFields);

        wrappedPrimitiveFields.forEach(primitiveField -> {
            // remove incoming old edges to primitive
            removeEdges.addAll(incomingEdgesOf(primitiveField));

            Set<ProtobufField> originatingFields = incomingEdgesOf(primitiveField).stream()
                    .map(FieldTypeEdge::getEdgeSource)
                    .filter(edgeSource -> !googleWrapped.contains(edgeSource))
                    .map(ProtobufField.class::cast)
                    .collect(Collectors.toSet());
            removeVertices.addAll(originatingFields);

            originatingFields.forEach(originatingField -> {
                // Replacement Field
                ProtobufElement fieldVertex = originatingField.toBuilder()
                        .fieldPathType(primitiveField.fieldPathType())
                        .schemaFieldDataType(primitiveField.schemaFieldDataType())
                        .isMessageType(false)
                        .build();
                addVertices.add(fieldVertex);

                // link source field parent directly to primitive
                Set<FieldTypeEdge> incomingEdges = incomingEdgesOf(originatingField);
                removeEdges.addAll(incomingEdgesOf(originatingField));
                addEdges.addAll(incomingEdges.stream().map(oldEdge ->
                        // Replace old edge with new edge to primitive
                        FieldTypeEdge.builder()
                                .edgeSource(oldEdge.getEdgeSource())
                                .edgeTarget(fieldVertex)
                                .type(primitiveField.fieldPathType())
                                .isMessageType(false) // known primitive
                                .build()).collect(Collectors.toSet()));
            });

            // remove old fields
            removeVertices.addAll(originatingFields);
        });

        // Remove edges
        removeAllEdges(removeEdges);
        // Remove vertices
        removeAllVertices(removeVertices);
        // Add vertices
        addVertices.forEach(this::addVertex);
        // Add edges
        addEdges.forEach(e -> e.inGraph(this));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        ProtobufGraph that = (ProtobufGraph) o;

        return rootProtobufMessage.equals(that.rootProtobufMessage);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + rootProtobufMessage.hashCode();
        return result;
    }

    public String getHash() {
        return String.valueOf(super.hashCode());
    }
}