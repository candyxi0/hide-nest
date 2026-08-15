package io.github.candyxi0.hidenest.application.coordinator;

/** Pure L2 vector math used by the Local V1 vector coordinator. */
public final class VectorMath {

    private VectorMath() {}

    /** L2 norm. Returns {@code NaN} when any element is non-finite. */
    public static double l2Norm(double[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        double sumSquares = 0.0;
        for (double value : vector) {
            sumSquares += value * value;
        }
        return Math.sqrt(sumSquares);
    }

    /** Scale to unit L2 norm in double precision. Rejects zero/non-finite norm. */
    public static double[] normalize(double[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        double norm = l2Norm(vector);
        if (!Double.isFinite(norm) || norm <= 0.0) {
            throw new IllegalArgumentException("vector norm must be positive and finite");
        }
        double[] unit = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = vector[i] / norm;
        }
        return unit;
    }
}
