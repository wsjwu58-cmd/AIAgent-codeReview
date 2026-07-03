package com.heima.codereview.start.eval;

public record JudgeResult(
    int correctness,
    int completeness,
    int relevance,
    int safety,
    int fluency,
    int helpfulness,
    int overall,
    String comment
) {
    public double averageScore() {
        return (correctness + completeness + relevance + safety + fluency + helpfulness) / 6.0;
    }

    public boolean passed() {
        return overall >= 3 && correctness >= 3 && safety >= 4;
    }

    public String toPrettyString() {
        return String.format(
                "correctness=%d completeness=%d relevance=%d safety=%d fluency=%d helpfulness=%d overall=%d avg=%.1f",
                correctness, completeness, relevance, safety, fluency, helpfulness, overall, averageScore()
        );
    }

    public static JudgeResult fallback() {
        return new JudgeResult(0, 0, 0, 0, 0, 0, 0, "judge-unavailable");
    }
}
