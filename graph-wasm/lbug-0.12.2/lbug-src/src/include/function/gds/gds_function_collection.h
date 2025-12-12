#pragma once

#include "function/gds/rec_joins.h"

namespace lbug {
namespace function {

struct VarLenJoinsFunction {
    static constexpr const char* name = "VAR_LEN_JOINS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct AllSPDestinationsFunction {
    static constexpr const char* name = "ALL_SP_DESTINATIONS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct AllSPPathsFunction {
    static constexpr const char* name = "ALL_SP_PATHS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct SingleSPDestinationsFunction {
    static constexpr const char* name = "SINGLE_SP_DESTINATIONS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct SingleSPPathsFunction {
    static constexpr const char* name = "SINGLE_SP_PATHS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct WeightedSPDestinationsFunction {
    static constexpr const char* name = "WEIGHTED_SP_DESTINATIONS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct WeightedSPPathsFunction {
    static constexpr const char* name = "WEIGHTED_SP_PATHS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

struct AllWeightedSPPathsFunction {
    static constexpr const char* name = "ALL_WEIGHTED_SP_PATHS";

    static std::unique_ptr<RJAlgorithm> getAlgorithm();
};

} // namespace function
} // namespace lbug
