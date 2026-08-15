package io.github.candyxi0.hidenest.application.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure L2 math unit tests: norm, normalization, zero/non-finite rejection, tolerance. */
class VectorMathTest {

    @Test
    void l2NormComputesEuclideanNorm() {
        assertEquals(5.0, VectorMath.l2Norm(new double[] {3, 4}), 1e-12);
        assertEquals(1.0, VectorMath.l2Norm(new double[] {1, 0, 0, 0}), 1e-12);
    }

    @Test
    void normalizeProducesUnitVectorInDoublePrecision() {
        double[] unit = VectorMath.normalize(new double[] {3, 4});
        assertEquals(0.6, unit[0], 1e-12);
        assertEquals(0.8, unit[1], 1e-12);
        double norm = VectorMath.l2Norm(unit);
        assertTrue(Math.abs(norm - 1.0) <= 1e-6, "normalized norm must be within tolerance");
    }

    @Test
    void normalizeRejectsZeroVector() {
        assertThrows(IllegalArgumentException.class, () -> VectorMath.normalize(new double[] {0, 0, 0}));
    }

    @Test
    void normalizeRejectsNonFiniteNorm() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VectorMath.normalize(new double[] {Double.POSITIVE_INFINITY, 1, 1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> VectorMath.normalize(new double[] {Double.NaN, 1, 1}));
    }

    @Test
    void normalizedNormToleranceIsMetForLargeAndSmallVectors() {
        assertTrue(Math.abs(VectorMath.l2Norm(VectorMath.normalize(new double[] {1e150, 2e150})) - 1.0) <= 1e-6);
        assertTrue(Math.abs(VectorMath.l2Norm(VectorMath.normalize(new double[] {1e-150, 2e-150})) - 1.0) <= 1e-6);
    }
}
