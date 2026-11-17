package io.mosire.bma3.qqofficial.gateway;

/**
 * QQ Gateway close-code policy.
 *
 * <p>4006/4007 cannot resume and force a fresh Identify. 4008/4009 are recoverable and retain the
 * session for Resume. 4014/4914/4915 are terminal: retrying would only hammer the platform.</p>
 */
public final class GatewayCloseCode {

    private GatewayCloseCode() {
    }

    public enum Action {
        RECONNECT_IDENTIFY,
        RECONNECT_RESUME,
        TERMINAL,
        UNKNOWN
    }

    public record Classification(Action action, String errorCode, String terminalReason,
                                 String hint) {

        public boolean terminal() {
            return action == Action.TERMINAL;
        }

        public boolean clearSession() {
            return action == Action.RECONNECT_IDENTIFY;
        }
    }

    public static Classification classify(int closeCode) {
        return switch (closeCode) {
            case 4006 -> classify(Action.RECONNECT_IDENTIFY, "INVALID_SESSION",
                    "", "session invalid; identify again");
            case 4007 -> classify(Action.RECONNECT_IDENTIFY, "INVALID_SEQUENCE",
                    "", "sequence invalid; identify again");
            case 4008 -> classify(Action.RECONNECT_RESUME, "RATE_LIMITED",
                    "", "gateway rate limited; back off and resume");
            case 4009 -> classify(Action.RECONNECT_RESUME, "PAYLOAD_REJECTED",
                    "", "gateway rejected payload; back off and resume");
            case 4003 -> classify(Action.RECONNECT_IDENTIFY, "NOT_AUTHENTICATED",
                    "", "not authenticated; identify again");
            case 4014 -> classify(Action.TERMINAL, "INTENT_NOT_AUTHORIZED",
                    "UNSUPPORTED_INTENT", "intent is not authorized for this bot");
            case 4914 -> classify(Action.TERMINAL, "BOT_OFFLINE",
                    "BOT_OFFLINE", "bot is offline or only sandbox connectivity is allowed");
            case 4915 -> classify(Action.TERMINAL, "BOT_BANNED",
                    "BOT_BANNED", "bot has been banned");
            case 4001 -> classify(Action.TERMINAL, "INVALID_OPCODE",
                    "PROTOCOL_UNSUPPORTED", "gateway rejected the opcode");
            case 4002 -> classify(Action.TERMINAL, "INVALID_PAYLOAD",
                    "PROTOCOL_UNSUPPORTED", "gateway rejected the payload");
            case 4004 -> classify(Action.TERMINAL, "AUTHENTICATION_FAILED",
                    "AUTHENTICATION_FAILED", "gateway authentication failed");
            case 4010 -> classify(Action.TERMINAL, "INVALID_SHARD",
                    "UNSUPPORTED_SHARD", "gateway rejected the shard");
            case 4011 -> classify(Action.TERMINAL, "SHARD_REQUIRED",
                    "UNSUPPORTED_SHARD", "gateway requires a shard");
            case 4012 -> classify(Action.TERMINAL, "INVALID_VERSION",
                    "PROTOCOL_UNSUPPORTED", "gateway rejected the protocol version");
            case 4013 -> classify(Action.TERMINAL, "INVALID_INTENT",
                    "UNSUPPORTED_INTENT", "gateway rejected the intent");
            default -> classify(Action.UNKNOWN, "CLOSE_" + closeCode,
                    "", "transport closed; resume when possible");
        };
    }

    private static Classification classify(Action action, String code, String terminalReason,
                                           String hint) {
        return new Classification(action, code, terminalReason, hint);
    }
}
