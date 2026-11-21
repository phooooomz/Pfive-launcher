// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package lx.fixture;

/** Shaped like an AIDL interface, for lxdb's reader of framework.jar. tests/data/lxdb/make-fixtures.sh builds it. */
public interface IThing {
    int call(String name, int count);

    void over(long big, byte[] bytes);

    void over(int small);

    abstract class Stub implements IThing {
        public int ignored = 3;
        public static final String LABEL = "text comes before the numbers";
        public static final boolean ON = true;
        static final int TRANSACTION_call = 1 + 6;
        static final int TRANSACTION_over = 300;
        static final int TRANSACTION_negative = -2;
        static final int TRANSACTION_large = 0x12345678;
        static int notConstant = Integer.parseInt("9");
    }
}
