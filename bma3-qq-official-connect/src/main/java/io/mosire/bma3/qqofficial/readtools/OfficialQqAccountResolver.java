package io.mosire.bma3.qqofficial.readtools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic account selection shared by all official read tools.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>If a raw official source carries an account id, that account is authoritative. An explicit
 *       {@code accountId}, when also supplied, must match exactly.</li>
 *   <li>Without a source account, an explicit safe {@code accountId} wins.</li>
 *   <li>Otherwise exactly one known account is selected automatically; zero/multiple accounts are
 *       rejected rather than guessing.</li>
 * </ul>
 */
public final class OfficialQqAccountResolver {

    private static final int MAX_ACCOUNT_CODE_POINTS = 128;

    private final OfficialQqAccountView accounts;

    public OfficialQqAccountResolver(OfficialQqAccountView accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /** Read-only view used by the local account-list tool. */
    public List<OfficialQqAccountStatus> statuses() {
        return accounts.statuses();
    }

    public ResolvedAccount resolve(ToolContext toolContext, SourceAddress source) {
        Objects.requireNonNull(toolContext, "toolContext");
        String explicit = explicitAccountId(toolContext);
        String sourceAccount = source == null ? "" : source.accountId();

        String selected;
        if (!sourceAccount.isBlank()) {
            requireSafeAccountId(sourceAccount);
            if (!explicit.isBlank() && !explicit.equals(sourceAccount)) {
                throw new OfficialQqReadException(
                        OfficialQqReadException.Code.ACCOUNT_SOURCE_MISMATCH,
                        "accountId 与官方 source 中的账号不一致");
            }
            selected = sourceAccount;
        } else if (!explicit.isBlank()) {
            selected = requireSafeAccountId(explicit);
        } else {
            List<OfficialQqAccountStatus> known = accounts.statuses();
            if (known.isEmpty()) {
                throw new OfficialQqReadException(
                        OfficialQqReadException.Code.ACCOUNT_NOT_FOUND,
                        "未发现已配置的 QQ 官方账号");
            }
            if (known.size() != 1) {
                throw new OfficialQqReadException(
                        OfficialQqReadException.Code.AMBIGUOUS_ACCOUNT,
                        "存在多个 QQ 官方账号，请显式指定 accountId");
            }
            selected = known.getFirst().accountId();
        }

        OfficialQqAccountStatus status = accounts.status(selected)
                .orElseThrow(() -> new OfficialQqReadException(
                        OfficialQqReadException.Code.ACCOUNT_NOT_FOUND,
                        "未发现指定的 QQ 官方账号"));
        return new ResolvedAccount(selected, status);
    }

    /** API-backed reads must not pretend a configured but disconnected runtime is usable. */
    public ResolvedAccount resolveApiCapable(ToolContext toolContext, SourceAddress source) {
        ResolvedAccount resolved = resolve(toolContext, source);
        if (!resolved.status().enabled() || !resolved.status().connected()) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.ACCOUNT_UNAVAILABLE,
                    "指定的 QQ 官方账号当前不可用（未启用或未连接）");
        }
        return resolved;
    }

    private static String explicitAccountId(ToolContext toolContext) {
        if (!toolContext.arguments().containsKey("accountId")) {
            return "";
        }
        Object raw = toolContext.arguments().get("accountId");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.INVALID_ARGUMENT,
                    "accountId 必须是非空字符串");
        }
        return requireSafeAccountId(value);
    }

    private static String requireSafeAccountId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.equals(value.strip())) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.INVALID_ARGUMENT,
                    "accountId 格式无效");
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints > MAX_ACCOUNT_CODE_POINTS) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.INVALID_ARGUMENT,
                    "accountId 格式无效");
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/' || codePoint == '\\' || codePoint == '|'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new OfficialQqReadException(
                        OfficialQqReadException.Code.INVALID_ARGUMENT,
                        "accountId 格式无效");
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    public record ResolvedAccount(String accountId, OfficialQqAccountStatus status) {
        public ResolvedAccount {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(status, "status");
        }
    }
}
