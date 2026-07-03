package com.heima.codereview.start.eval;

public record SecurityBenchmarkResult(
    double tpr,
    double fpr,
    double precision,
    double f1,
    int truePositives,
    int falsePositives,
    int trueNegatives,
    int falseNegatives
) {
    public int total() {
        return truePositives + falsePositives + trueNegatives + falseNegatives;
    }

    public double accuracy() {
        int total = total();
        return total == 0 ? 0 : (double) (truePositives + trueNegatives) / total;
    }

    public String toPrettyString() {
        return String.format(
                "TPR(Recall)=%.2f%% FPR=%.2f%% Precision=%.2f%% F1=%.3f Accuracy=%.2f%% TP=%d FP=%d TN=%d FN=%d",
                tpr * 100, fpr * 100, precision * 100, f1, accuracy() * 100,
                truePositives, falsePositives, trueNegatives, falseNegatives
        );
    }
}
