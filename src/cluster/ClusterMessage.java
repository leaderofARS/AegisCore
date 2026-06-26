package cluster;

/**
 * Message payload exchanged between AegisCore cluster nodes.
 *
 * <p>Uses a simple, fast pipe-separated ASCII format for zero-dependency serialization.
 */
public record ClusterMessage(String type, String senderNodeId, String[] args) {

    public static ClusterMessage parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0];
        String senderNodeId = parts[1];
        String[] args = new String[parts.length - 2];
        System.arraycopy(parts, 2, args, 0, args.length);
        return new ClusterMessage(type, senderNodeId, args);
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("|").append(senderNodeId);
        for (String arg : args) {
            sb.append("|").append(arg == null ? "" : arg.replace("|", "\\|"));
        }
        return sb.toString();
    }
}
