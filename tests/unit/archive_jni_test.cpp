// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Runs tests/jni/ArchiveJniCheck.java against the archive JNI glue built for
// the host, under -Xcheck:jni. /CMakeLists.txt builds that library only when it
// finds a JDK, and never in the sanitizer build; the test is skipped otherwise.
#include <gtest/gtest.h>

#include <string>

#include "archive_test_util.hpp"

TEST(ArchiveJni, HarnessUnderCheckJni) {
#if !defined(LX_JNI_HOST_LIB) || !defined(LX_JAVA)
    GTEST_SKIP() << "the JNI glue was not built for the host (no JDK, or a sanitizer build)";
#else
    lxtest::ScratchDir dir;
    const std::string harness = std::string(LX_TEST_DATA_DIR) + "/../jni/ArchiveJniCheck.java";
    const std::string cmd = std::string("'") + LX_JAVA +
                            "' -Xcheck:jni --add-opens java.base/java.io=ALL-UNNAMED '" + harness + "' '" +
                            LX_JNI_HOST_LIB + "' '" + dir.path() + "' 2>&1";
    int status = 0;
    const std::string out = lxtest::runCommand(cmd, &status);
    EXPECT_EQ(status, 0) << out;
    EXPECT_NE(out.find("ALL PASS"), std::string::npos) << out;
    // CheckJNI reports misuse such as a call made with an exception pending as a WARNING.
    EXPECT_EQ(out.find("WARNING"), std::string::npos) << out;
    EXPECT_EQ(out.find("FATAL"), std::string::npos) << out;
#endif
}
