package io.github.c1589190.bma3.qqconnect.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import java.util.List;

/** Tool catalog for the QQ module. */
public final class QqTools {

    private QqTools() {
    }

    public static List<AgentTool> all(QqToolContext context) {
        return QqToolSupport.all(context);
    }
}
