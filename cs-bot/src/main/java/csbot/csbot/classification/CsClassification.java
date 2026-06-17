package csbot.csbot.classification;

public record CsClassification(Urgency urgency, String category, boolean requiresHuman) {

    public enum Urgency {
        HIGH, MEDIUM, LOW
    }
}
