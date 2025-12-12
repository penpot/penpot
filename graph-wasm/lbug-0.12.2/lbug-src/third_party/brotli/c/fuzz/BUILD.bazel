load("@rules_fuzzing//fuzzing:cc_defs.bzl", "cc_fuzz_test")

package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # MIT

# To start fuzzing run: bazel run --config=asan-libfuzzer //:fuzz_config_run
cc_fuzz_test(
    name = "decode_fuzzer",
    srcs = ["decode_fuzzer.c"],
    deps = ["@org_brotli//:brotlidec"],
)
