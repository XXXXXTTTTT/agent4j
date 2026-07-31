package com.agent.core.nodes;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.Node;
import com.agent.sandbox.ast.AstService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 应用状态中 Unified Diff 的代码修改节点。 */
public final class CoderNode implements Node {

    public static final String WORKSPACE_PATH_KEY = "coder.workspacePath";
    public static final String UNIFIED_DIFF_KEY = "coder.unifiedDiff";
    public static final String UPDATED_FILES_KEY = "coder.updatedFiles";
    public static final String ERROR_KEY = "coder.error";

    private final AstService astService;

    /**
     * 创建代码修改节点。
     *
     * @param astService AST 与 Diff 服务
     */
    public CoderNode(AstService astService) {
        this.astService = Objects.requireNonNull(astService, "astService 不能为空");
    }

    /**
     * 应用状态中的补丁，并返回包含更新文件的新状态。
     *
     * @param state 输入状态
     * @return 节点执行后的新状态
     */
    @Override
    public AgentState execute(AgentState state) {
        Objects.requireNonNull(state, "state 不能为空");
        try {
            Path workspace = Path.of(requireVariable(state, WORKSPACE_PATH_KEY))
                    .toAbsolutePath()
                    .normalize();
            String unifiedDiff = requireVariable(state, UNIFIED_DIFF_KEY);
            List<Path> updatedFiles = astService.applyDiff(workspace, unifiedDiff);
            String relativeFiles = updatedFiles.stream()
                    .map(workspace::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .collect(Collectors.joining("\n"));
            return state
                    .withVariable(UPDATED_FILES_KEY, relativeFiles)
                    .withTraceEntry("coder");
        } catch (Exception exception) {
            return state
                    .withVariable(ERROR_KEY, stackTrace(exception))
                    .withTraceEntry("coder");
        }
    }

    private String requireVariable(AgentState state, String key) {
        String value = state.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少状态变量: " + key);
        }
        return value;
    }

    private String stackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
