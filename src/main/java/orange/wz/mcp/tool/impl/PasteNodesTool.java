package orange.wz.mcp.tool.impl;

import orange.wz.mcp.dto.OverwriteStrategy;
import orange.wz.mcp.service.McpWorkspaceService;
import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.tool.support.BaseSessionTool;
import orange.wz.mcp.tool.support.ToolParamHelper;

import java.util.Locale;
import java.util.List;
import java.util.Map;

import static orange.wz.mcp.tool.support.ToolSchemas.*;

public final class PasteNodesTool extends BaseSessionTool {
    private final McpWorkspaceService service;

    public PasteNodesTool(McpSessionManager sessionManager, McpWorkspaceService service) {
        super(sessionManager, "将会话剪贴板内容粘贴到目标节点。", objectSchema(
                Map.of(
                        "rootPath", stringSchema(),
                        "nodePath", stringSchema(),
                        "strategy", stringSchema(),
                        "autoParse", booleanSchema()
                ),
                List.of("rootPath")
        ));
        this.service = service;
    }

    @Override
    public String name() {
        return "paste_nodes";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        var session = session(params);
        var target = ToolParamHelper.getNodeReference(params);
        boolean autoParse = ToolParamHelper.getBoolean(params, "autoParse", true);
        String strategyText = ToolParamHelper.getString(params, "strategy", OverwriteStrategy.ERROR.name());
        OverwriteStrategy strategy = OverwriteStrategy.valueOf(strategyText.toUpperCase(Locale.ROOT));
        return Map.of("pasted", service.pasteToNode(session, target, strategy, autoParse));
    }
}
