package com.agent.sandbox.docker;

import com.agent.sandbox.pty.DockerTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Volume;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 解析 Docker Engine 可见的工作区 bind source。 */
public final class DockerWorkspaceBindResolver {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private DockerWorkspaceBindResolver() { }
    public static List<InspectContainerResponse.Mount> parseMounts(String inspectJson) {
        Objects.requireNonNull(inspectJson, "inspectJson 不能为空");
        try { JsonNode root=OBJECT_MAPPER.readTree(inspectJson); if(root==null||!root.isObject()||!root.path("Mounts").isArray()) return List.of(); List<InspectContainerResponse.Mount> mounts=new ArrayList<>(); for(JsonNode node:root.path("Mounts")){ if(!node.isObject()) continue; InspectContainerResponse.Mount mount=new InspectContainerResponse.Mount(); if(node.path("Destination").isTextual()) mount.withDestination(new Volume(node.path("Destination").textValue())); if(node.path("Source").isTextual()) mount.withSource(node.path("Source").textValue()); if(node.path("Name").isTextual()) mount.withName(node.path("Name").textValue()); if(node.path("RW").isBoolean()) mount.withRw(node.path("RW").booleanValue()); mounts.add(mount);} return List.copyOf(mounts); }
        catch (Exception exception) { throw new IllegalArgumentException("Docker Inspect 响应不是有效 JSON", exception); }
    }
    public static String resolveWorkspaceBindSource(String bindRoot, DockerTarget.ContainerWorkspaceSource source) { Objects.requireNonNull(source,"source 不能为空"); if(bindRoot==null||bindRoot.isBlank()) throw new IllegalArgumentException("Docker bind root 不能为空"); if(source.relativePath().isEmpty()) return bindRoot; char separator=bindRoot.indexOf('\\')>=0?'\\':'/'; String suffix=source.relativePath().replace('/',separator); return bindRoot.endsWith("/")||bindRoot.endsWith("\\")?bindRoot+suffix:bindRoot+separator+suffix; }
    public static String resolveContainerBindSource(DockerTarget.ContainerWorkspaceSource source, List<InspectContainerResponse.Mount> mounts) { Objects.requireNonNull(source,"source 不能为空"); Objects.requireNonNull(mounts,"mounts 不能为空"); List<InspectContainerResponse.Mount> matches=mounts.stream().filter(Objects::nonNull).filter(m->m.getDestination()!=null&&source.containerPath().equals(m.getDestination().getPath())).toList(); if(matches.isEmpty()) throw new IllegalArgumentException("源容器未找到工作区 mount: "+source.containerPath()); if(matches.size()!=1) throw new IllegalArgumentException("源容器工作区 mount 必须唯一: "+source.containerPath()); var mount=matches.getFirst(); if(!Boolean.TRUE.equals(mount.getRW())) throw new IllegalArgumentException("源容器工作区 mount 必须可读写"); if(mount.getName()!=null&&!mount.getName().isBlank()) throw new IllegalArgumentException("源容器工作区 mount 必须是 bind"); if(mount.getSource()==null||mount.getSource().isBlank()) throw new IllegalArgumentException("源容器工作区 bind source 不能为空"); return mount.getSource(); }
    /** 统一解析宿主或源容器工作区在 Docker Engine 中可见的 bind source。 */
    public static String resolveBindSource(
            DockerTarget target, List<InspectContainerResponse.Mount> containerMounts) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(containerMounts, "containerMounts 不能为空");
        return switch (target.workspaceSource()) {
            case DockerTarget.HostWorkspaceSource ignored -> target.hostWorkspace().toString();
            case DockerTarget.ContainerWorkspaceSource source -> resolveWorkspaceBindSource(
                    resolveContainerBindSource(source, containerMounts), source);
        };
    }
}
