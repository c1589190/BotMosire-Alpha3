package io.mosire.bma3.qqofficial.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrAccountResolver;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrService;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Composition helper for the runtime official-QQ share-QR AgentTool. */
public final class QqOfficialShareQrTools {

    private QqOfficialShareQrTools() {
    }

    public static List<AgentTool> all(
            Path dataDir, OfficialQqShareQrAccountResolver accountResolver) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(accountResolver, "accountResolver");
        return List.of(new QqOfficialShareQrTool(
                new OfficialQqShareQrService(dataDir, accountResolver)));
    }
}
