/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package hivemall.utils.math;

import hivemall.utils.collections.lists.DoubleArrayList;
import hivemall.utils.lang.Preconditions;

import java.util.Arrays;

import javax.annotation.Nonnull;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.BlockRealMatrix;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.DefaultRealMatrixPreservingVisitor;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealMatrixPreservingVisitor;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.SingularMatrixException;
import org.apache.commons.math3.linear.SingularValueDecomposition;

public final class MatrixUtils {

    private MatrixUtils() {}

    /**
     * Solve Yule-walker equation by Levinson-Durbin Recursion.
     *
     * <pre>
     * R_j = ∑_{i=1}^{k} A_i R_{j-i} where j = 1..k, R_{-i} = R'_i
     * </pre>
     *
     * @see <a href=
     *      "http://www.emptyloop.com/technotes/a%20tutorial%20on%20linear%20prediction%20and%20levinson-durbin.pdf">Cedrick
     *      Collomb: A tutorial on linear prediction and Levinson-Durbin</a>
     * @param R autocovariance where |R| >= order
     * @param A coefficient to be solved where |A| >= order + 1
     * @return E variance of prediction error
     */
    @Nonnull
    public static double[] aryule(@Nonnull final double[] R, @Nonnull final double[] A,
            final int order) {
        Preconditions.checkArgument(R.length > order,
            "|R| MUST be greater than or equals to " + order + ": " + R.length);
        Preconditions.checkArgument(A.length >= order + 1,
            "|A| MUST be greater than or equals to " + (order + 1) + ": " + A.length);

        final double[] E = new double[order + 1];
        A[0] = 1.0d;
        E[0] = R[0];
        for (int k = 0; k < order; k++) {
            double lambda = 0.d;
            for (int j = 0; j <= k; j++) {
                lambda -= A[j] * R[k + 1 - j];
            }
            final double Ek = E[k];
            if (Ek == 0.d) {
                lambda = 0.d;
            } else {
                lambda /= Ek;
            }

            for (int n = 0, last = (k + 1) / 2; n <= last; n++) {
                final int i = k + 1 - n;
                double tmp = A[i] + lambda * A[n];
                A[n] += lambda * A[i];
                A[i] = tmp;
            }

            E[k + 1] = Ek * (1.0d - lambda * lambda);
        }

        for (int i = 0; i < order + 1; i++) {
            A[i] = -A[i];
        }

        return E;
    }

    @Deprecated
    @Nonnull
    public static double[] aryule2(@Nonnull final double[] R, @Nonnull final double[] A,
            final int order) {
        Preconditions.checkArgument(R.length > order,
            "|C| MUST be greater than or equals to " + order + ": " + R.length);
        Preconditions.checkArgument(A.length >= order + 1,
            "|A| MUST be greater than or equals to " + (order + 1) + ": " + A.length);

        final double[] E = new double[order + 1];
        A[0] = E[0] = 1.0d;
        A[1] = -R[1] / R[0];
        E[1] = R[0] + R[1] * A[1];
        for (int k = 1; k < order; k++) {
            double lambda = 0.d;
            for (int j = 0; j <= k; j++) {
                lambda -= A[j] * R[k + 1 - j];
            }
            lambda /= E[k];

            final double[] U = new double[k + 2];
            final double[] V = new double[k + 2];
            U[0] = 1.0; // V[0] = 0.0;
            for (int i = 1; i <= k; i++) {
                U[i] = A[i];
                V[k + 1 - i] = A[i];
            }
            V[k + 1] = 1.0; // U[k + 1] = 0.0;
            for (int i = 0, threshold = k + 2; i < threshold; i++) {
                A[i] = U[i] + lambda * V[i];
            }

            E[k + 1] = E[k] * (1.0d - lambda * lambda);
        }

        for (int i = 0; i < order + 1; i++) {
            A[i] = -A[i];
        }

        return E;
    }

    /**
     * Fit an AR(order) model using the Burg's method.
     *
     * @see https://searchcode.com/codesearch/view/9503568/
     * @param X data vector to estimate where |X| >= order
     * @param A coefficient to be solved where |A| >= order + 1
     * @return E variance of white noise
     */
    @Nonnull
    public static double[] arburg(@Nonnull final double[] X, @Nonnull final double[] A,
            final int order) {
        Preconditions.checkArgument(X.length > order,
            "|X| MUST be greater than or equals to " + order + ": " + X.length);
        Preconditions.checkArgument(A.length >= order + 1,
            "|A| MUST be greater than or equals to " + (order + 1) + ": " + A.length);

        final int nDataPoints = X.length;
        final double[] E = new double[order + 1];

        E[0] = 0.0d;
        for (int i = 0; i < nDataPoints; i++) {
            E[0] += X[i] * X[i];
        }

        // f and b are the forward and backward error sequences
        int currentErrorSequenceSize = nDataPoints - 1;
        double[] F = new double[currentErrorSequenceSize];
        double[] B = new double[currentErrorSequenceSize];
        for (int i = 0; i < currentErrorSequenceSize; i++) {
            F[i] = X[i + 1];
            B[i] = X[i];
        }

        A[0] = 1.0d;

        // remaining stages i=2 to p
        for (int i = 0; i < order; i++) {

            // get the i-th reflection coefficient
            double numerator = 0.0d;
            double denominator = 0.0d;
            for (int j = 0; j < currentErrorSequenceSize; j++) {
                numerator += F[j] * B[j];
                denominator += F[j] * F[j] + B[j] * B[j];
            }
            numerator *= 2.0d;
            double g = 0.0d;
            if (denominator != 0.0d) {
                g = numerator / denominator;
            }

            // generate next filter order
            final double[] prevA = new double[i];
            for (int j = 0; j < i; j++) {
                // No need to copy A[0] = 1.0
                prevA[j] = A[j + 1];
            }
            A[1] = g;
            for (int j = 1; j < i + 1; j++) {
                A[j + 1] = prevA[j - 1] - g * prevA[i - j];
            }

            // keep track of the error
            E[i + 1] = E[i] * (1 - g * g);

            // update the prediction error sequences
            final double[] prevF = new double[currentErrorSequenceSize];
            for (int j = 0; j < currentErrorSequenceSize; j++) {
                prevF[j] = F[j];
            }
            final int nextErrorSequenceSize = nDataPoints - i - 2;
            for (int j = 0; j < nextErrorSequenceSize; j++) {
                F[j] = prevF[j + 1] - g * B[j + 1];
                B[j] = B[j] - g * prevF[j];
            }

            currentErrorSequenceSize = nextErrorSequenceSize;

        }

        for (int i = 1, mid = order / 2 + 1; i < mid; i++) {
            // Reverse 1..(order - 1)-th elements by swapping
            final double tmp = A[i];
            A[i] = A[order + 1 - i];
            A[order + 1 - i] = tmp;
        }

        for (int i = 0; i < order + 1; i++) {
            A[i] = -A[i];
        }

        return E;
    }

    /**
     * Construct a Toeplitz matrix.
     */
    @Nonnull
    public static RealMatrix[][] toeplitz(@Nonnull final RealMatrix[] c, final int dim) {
        Preconditions.checkArgument(dim >= 1, "Invalid dimension: " + dim);
        Preconditions.checkArgument(c.length >= dim,
            "|c| must be greater than " + dim + ": " + c.length);

        /*
         * Toeplitz matrix  (symmetric, invertible, k*dimensions by k*dimensions)
         *
         * /C_0     |C_1'    |C_2'     | .  .  .  |C_{k-1}' \
         * |--------+--------+--------+           +---------|
         * |C_1     |C_0     |C_1'     |               .    |
         * |--------+--------+--------+                .    |
         * |C_2     |C_1     |C_0      |               .    |
         * |--------+--------+--------+                     |
         * |   .                         .                  |
         * |   .                            .               |
         * |   .                               .            |
         * |--------+                              +--------|
         * \C_{k-1} | .  .  .                      |C_0     /
         */
        final RealMatrix c0 = c[0];
        final RealMatrix[][] toeplitz = new RealMatrix[dim][dim];
        for (int row = 0; row < dim; row++) {
            toeplitz[row][row] = c0;
            for (int col = 0; col < dim; col++) {
                if (row < col) {
                    toeplitz[row][col] = c[col - row].transpose();
                } else if (row > col) {
                    toeplitz[row][col] = c[row - col];
                }
            }
        }
        return toeplitz;
    }

    /**
     * Construct a Toeplitz matrix.
     */
    @Nonnull
    public static double[][] toeplitz(@Nonnull final double[] c) {
        return toeplitz(c, c.length);
    }

    /**
     * Construct a Toeplitz matrix.
     */
    @Nonnull
    public static double[][] toeplitz(@Nonnull final double[] c, final int dim) {
        Preconditions.checkArgument(dim >= 1, "Invalid dimension: " + dim);
        Preconditions.checkArgument(c.length >= dim,
            "|c| must be greater than " + dim + ": " + c.length);

        /*
         * Toeplitz matrix  (symmetric, invertible, k*dimensions by k*dimensions)
         *
         * /C_0     |C_1'    |C_2'     | .  .  .  |C_{k-1}' \
         * |--------+--------+--------+           +---------|
         * |C_1     |C_0     |C_1'     |               .    |
         * |--------+--------+--------+                .    |
         * |C_2     |C_1     |C_0      |               .    |
         * |--------+--------+--------+                     |
         * |   .                         .                  |
         * |   .                            .               |
         * |   .                               .            |
         * |--------+                              +--------|
         * \C_{k-1} | .  .  .                      |C_0     /
         */
        final double c0 = c[0];
        final double[][] toeplitz = new double[dim][dim];
        for (int row = 0; row < dim; row++) {
            toeplitz[row][row] = c0;
            for (int col = 0; col < dim; col++) {
                if (row < col) {
                    toeplitz[row][col] = c[col - row];
                } else if (row > col) {
                    toeplitz[row][col] = c[row - col];
                }
            }
        }
        return toeplitz;
    }

    @Nonnull
    public static double[] flatten(@Nonnull final RealMatrix[][] grid) {
        Preconditions.checkArgument(grid.length >= 1, "The number of rows must be greater than 1");
        Preconditions.checkArgument(grid[0].length >= 1,
            "The number of cols must be greater than 1");

        final int rows = grid.length;
        final int cols = grid[0].length;
        RealMatrix grid00 = grid[0][0];
        Preconditions.checkNotNull(grid00);
        int cellRows = grid00.getRowDimension();
        int cellCols = grid00.getColumnDimension();

        final DoubleArrayList list = new DoubleArrayList(rows * cols * cellRows * cellCols);
        final RealMatrixPreservingVisitor visitor = new DefaultRealMatrixPreservingVisitor() {
            @Override
            public void visit(int row, int column, double value) {
                list.add(value);
            }
        };

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                RealMatrix cell = grid[row][col];
                cell.walkInRowOrder(visitor);
            }
        }

        return list.toArray();
    }

    @Nonnull
    public static double[] flatten(@Nonnull final RealMatrix[] grid) {
        Preconditions.checkArgument(grid.length >= 1, "The number of rows must be greater than 1");

        final int rows = grid.length;
        RealMatrix grid0 = grid[0];
        Preconditions.checkNotNull(grid0);
        int cellRows = grid0.getRowDimension();
        int cellCols = grid0.getColumnDimension();

        final DoubleArrayList list = new DoubleArrayList(rows * cellRows * cellCols);
        final RealMatrixPreservingVisitor visitor = new DefaultRealMatrixPreservingVisitor() {
            @Override
            public void visit(int row, int column, double value) {
                list.add(value);
            }
        };

        for (int row = 0; row < rows; row++) {
            RealMatrix cell = grid[row];
            cell.walkInRowOrder(visitor);
        }

        return list.toArray();
    }

    @Nonnull
    public static RealMatrix[] unflatten(@Nonnull final double[] data, final int rows,
            final int cols, final int len) {
        final RealMatrix[] grid = new RealMatrix[len];
        int offset = 0;
        for (int k = 0; k < len; k++) {
            RealMatrix cell = new BlockRealMatrix(rows, cols);
            grid[k] = cell;
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    if (offset >= data.length) {
                        throw new IndexOutOfBoundsException(
                            "Offset " + offset + " exceeded data.length " + data.length);
                    }
                    double value = data[offset];
                    cell.setEntry(i, j, value);
                    offset++;
                }
            }
        }
        if (offset != data.length) {
            throw new IllegalArgumentException("Invalid data for unflatten");
        }
        return grid;
    }

    @Nonnull
    public static RealMatrix combinedMatrices(@Nonnull final RealMatrix[][] grid,
            final int dimensions) {
        Preconditions.checkArgument(grid.length >= 1, "The number of rows must be greater than 1");
        Preconditions.checkArgument(grid[0].length >= 1,
            "The number of cols must be greater than 1");
        Preconditions.checkArgument(dimensions > 0, "Dimension should be more than 0: ",
            dimensions);

        final int rows = grid.length;
        final int cols = grid[0].length;

        final RealMatrix combined = new BlockRealMatrix(rows * dimensions, cols * dimensions);
        for (int row = 0; row < grid.length; row++) {
            for (int col = 0; col < grid[row].length; col++) {
                combined.setSubMatrix(grid[row][col].getData(), row * dimensions, col * dimensions);
            }
        }
        return combined;
    }

    @Nonnull
    public static RealMatrix combinedMatrices(@Nonnull final RealMatrix[] grid) {
        Preconditions.checkArgument(grid.length >= 1,
            "The number of rows must be greater than 0: " + grid.length);

        final int rows = grid.length;
        final int rowDims = grid[0].getRowDimension();
        final int colDims = grid[0].getColumnDimension();

        final RealMatrix combined = new BlockRealMatrix(rows * rowDims, colDims);
        for (int row = 0; row < grid.length; row++) {
            RealMatrix cell = grid[row];
            Preconditions.checkArgument(cell.getRowDimension() == rowDims,
                "Mismatch in row dimensions at row ", row);
            Preconditions.checkArgument(cell.getColumnDimension() == colDims,
                "Mismatch in col dimensions at row ", row);
            combined.setSubMatrix(cell.getData(), row * rowDims, 0);
        }
        return combined;
    }

    @Nonnull
    public static RealMatrix inverse(@Nonnull final RealMatrix m) throws SingularMatrixException {
        return inverse(m, true);
    }

    @Nonnull
    public static RealMatrix inverse(@Nonnull final RealMatrix m, final boolean exact)
            throws SingularMatrixException {
        LUDecomposition LU = new LUDecomposition(m);
        DecompositionSolver solver = LU.getSolver();
        final RealMatrix inv;
        if (exact || solver.isNonSingular()) {
            inv = solver.getInverse();
        } else {
            SingularValueDecomposition SVD = new SingularValueDecomposition(m);
            inv = SVD.getSolver().getInverse();
        }
        return inv;
    }

    public static double det(@Nonnull final RealMatrix m) {
        LUDecomposition LU = new LUDecomposition(m);
        return LU.getDeterminant();
    }

    /**
     * Return a 2-D array with ones on the diagonal and zeros elsewhere.
     */
    @Nonnull
    public static double[][] eye(int n) {
        final double[][] eye = new double[n][n];
        for (int i = 0; i < n; i++) {
            eye[i][i] = 1;
        }
        return eye;
    }

    /**
     * L = A x R
     *
     * @return a matrix A that minimizes A x R - L
     */
    @Nonnull
    public static RealMatrix solve(@Nonnull final RealMatrix L, @Nonnull final RealMatrix R)
            throws SingularMatrixException {
        return solve(L, R, true);
    }

    /**
     * L = A x R
     *
     * @return a matrix A that minimizes A x R - L
     */
    @Nonnull
    public static RealMatrix solve(@Nonnull final RealMatrix L, @Nonnull final RealMatrix R,
            final boolean exact) throws SingularMatrixException {
        LUDecomposition LU = new LUDecomposition(R);
        DecompositionSolver solver = LU.getSolver();
        final RealMatrix A;
        if (exact || solver.isNonSingular()) {
            A = LU.getSolver().solve(L);
        } else {
            SingularValueDecomposition SVD = new SingularValueDecomposition(R);
            A = SVD.getSolver().solve(L);
        }
        return A;
    }

    /**
     * Find the first singular vector/value of a matrix A based on the Power method.
     *
     * @see http
     *      ://www.cs.yale.edu/homes/el327/datamining2013aFiles/07_singular_value_decomposition.pdf
     * @param A target matrix
     * @param x0 initial vector
     * @param nIter number of iterations for the Power method
     * @param u 1st left singular vector
     * @param v 1st right singular vector
     * @return 1st singular value
     */
    public static double power1(@Nonnull final RealMatrix A, @Nonnull final double[] x0,
            final int nIter, @Nonnull final double[] u, @Nonnull final double[] v) {
        Preconditions.checkArgument(A.getColumnDimension() == x0.length,
            "Column size of A and length of x should be same");
        Preconditions.checkArgument(A.getRowDimension() == u.length,
            "Row size of A and length of u should be same");
        Preconditions.checkArgument(x0.length == v.length, "Length of x and u should be same");
        Preconditions.checkArgument(nIter >= 1, "Invalid number of iterations: " + nIter);

        RealMatrix AtA = A.transpose().multiply(A);

        RealVector x = new ArrayRealVector(x0);
        for (int i = 0; i < nIter; i++) {
            x = AtA.operate(x);
        }

        double xNorm = x.getNorm();
        for (int i = 0, n = v.length; i < n; i++) {
            v[i] = x.getEntry(i) / xNorm;
        }

        RealVector Av = new ArrayRealVector(A.operate(v));
        double s = Av.getNorm();

        for (int i = 0, n = u.length; i < n; i++) {
            u[i] = Av.getEntry(i) / s;
        }

        return s;
    }

    /**
     * Lanczos tridiagonalization for a symmetric matrix C to make s * s tridiagonal matrix T.
     *
     * @see http://www.cas.mcmaster.ca/~qiao/publications/spie05.pdf
     * @param C target symmetric matrix
     * @param a initial vector
     * @param T result is stored here
     */
    public static void lanczosTridiagonalization(@Nonnull final RealMatrix C,
            @Nonnull final double[] a, @Nonnull final RealMatrix T) {
        Preconditions.checkArgument(Arrays.deepEquals(C.getData(), C.transpose().getData()),
            "Target matrix C must be a symmetric matrix");
        Preconditions.checkArgument(C.getColumnDimension() == a.length,
            "Column size of A and length of a should be same");
        Preconditions.checkArgument(T.getRowDimension() == T.getColumnDimension(),
            "T must be a square matrix");

        int s = T.getRowDimension();

        // initialize T with zeros
        T.setSubMatrix(new double[s][s], 0, 0);

        RealVector a0 = new ArrayRealVector(a.length);
        RealVector r = new ArrayRealVector(a);

        double beta0 = 1.d;

        for (int i = 0; i < s; i++) {
            RealVector a1 = r.mapDivide(beta0);
            RealVector Ca1 = C.operate(a1);

            double alpha1 = a1.dotProduct(Ca1);

            r = Ca1.add(a1.mapMultiply(-1.d * alpha1)).add(a0.mapMultiply(-1.d * beta0));

            double beta1 = r.getNorm();

            T.setEntry(i, i, alpha1);
            if (i - 1 >= 0) {
                T.setEntry(i, i - 1, beta0);
            }
            if (i + 1 < s) {
                T.setEntry(i, i + 1, beta1);
            }

            a0 = a1.copy();
            beta0 = beta1;
        }
    }

    /**
     * QR decomposition for a tridiagonal matrix T.
     *
     * @see https://gist.github.com/lightcatcher/8118181
     * @see http://www.ericmart.in/blog/optimizing_julia_tridiag_qr
     * @param T target tridiagonal matrix
     * @param R output matrix for R which is the same shape as T
     * @param Qt output matrix for Q.T which is the same shape an T
     */
    public static void tridiagonalQR(@Nonnull final RealMatrix T, @Nonnull final RealMatrix R,
            @Nonnull final RealMatrix Qt) {
        int n = T.getRowDimension();
        Preconditions.checkArgument(n == R.getRowDimension() && n == R.getColumnDimension(),
            "T and R must be the same shape");
        Preconditions.checkArgument(n == Qt.getRowDimension() && n == Qt.getColumnDimension(),
            "T and Qt must be the same shape");

        // initial R = T
        R.setSubMatrix(T.getData(), 0, 0);

        // initial Qt = identity
        Qt.setSubMatrix(eye(n), 0, 0);

        for (int i = 0; i < n - 1; i++) {
            // Householder projection for a vector x
            // https://en.wikipedia.org/wiki/Householder_transformation
            RealVector x = T.getSubMatrix(i, i + 1, i, i).getColumnVector(0);
            x = unitL2norm(x);

            RealMatrix subR = R.getSubMatrix(i, i + 1, 0, n - 1);
            R.setSubMatrix(
                subR.subtract(x.outerProduct(subR.preMultiply(x)).scalarMultiply(2)).getData(), i,
                0);

            RealMatrix subQt = Qt.getSubMatrix(i, i + 1, 0, n - 1);
            Qt.setSubMatrix(
                subQt.subtract(x.outerProduct(subQt.preMultiply(x)).scalarMultiply(2)).getData(), i,
                0);
        }
    }

    @Nonnull
    static RealVector unitL2norm(@Nonnull final RealVector x) {
        double x0 = x.getEntry(0);
        double sign = MathUtils.sign(x0);
        x.setEntry(0, x0 + sign * x.getNorm());
        return x.unitVector();
    }

    /**
     * Find eigenvalues and eigenvectors of given tridiagonal matrix T.
     *
     * @see http://web.csulb.edu/~tgao/math423/s94.pdf
     * @see http://stats.stackexchange.com/questions/20643/finding-matrix-eigenvectors-using-qr-
     *      decomposition
     * @param T target tridiagonal matrix
     * @param nIter number of iterations for the QR method
     * @param eigvals eigenvalues are stored here
     * @param eigvecs eigenvectors are stored here
     */
    public static void tridiagonalEigen(@Nonnull final RealMatrix T, @Nonnull final int nIter,
            @Nonnull final double[] eigvals, @Nonnull final RealMatrix eigvecs) {
        Preconditions.checkArgument(Arrays.deepEquals(T.getData(), T.transpose().getData()),
            "Target matrix T must be a symmetric (tridiagonal) matrix");
        Preconditions.checkArgument(eigvecs.getRowDimension() == eigvecs.getColumnDimension(),
            "eigvecs must be a square matrix");
        Preconditions.checkArgument(T.getRowDimension() == eigvecs.getRowDimension(),
            "T and eigvecs must be the same shape");
        Preconditions.checkArgument(eigvals.length == eigvecs.getRowDimension(),
            "Number of eigenvalues and eigenvectors must be same");

        int nEig = eigvals.length;

        // initialize eigvecs as an identity matrix
        eigvecs.setSubMatrix(eye(nEig), 0, 0);

        RealMatrix T_ = T.copy();

        for (int i = 0; i < nIter; i++) {
            // QR decomposition for the tridiagonal matrix T
            RealMatrix R = new Array2DRowRealMatrix(nEig, nEig);
            RealMatrix Qt = new Array2DRowRealMatrix(nEig, nEig);
            tridiagonalQR(T_, R, Qt);

            RealMatrix Q = Qt.transpose();
            T_ = R.multiply(Q);
            eigvecs.setSubMatrix(eigvecs.multiply(Q).getData(), 0, 0);
        }

        // diagonal elements correspond to the eigenvalues
        for (int i = 0; i < nEig; i++) {
            eigvals[i] = T_.getEntry(i, i);
        }
    }

}
