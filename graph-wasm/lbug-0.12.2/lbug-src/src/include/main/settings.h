#pragma once

#include "common/types/value/value.h"

namespace lbug {
namespace main {

struct ThreadsSetting {
    static constexpr auto name = "threads";
    static constexpr auto inputType = common::LogicalTypeID::UINT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct WarningLimitSetting {
    static constexpr auto name = "warning_limit";
    static constexpr auto inputType = common::LogicalTypeID::UINT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct TimeoutSetting {
    static constexpr auto name = "timeout";
    static constexpr auto inputType = common::LogicalTypeID::UINT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct ProgressBarSetting {
    static constexpr auto name = "progress_bar";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct VarLengthExtendMaxDepthSetting {
    static constexpr auto name = "var_length_extend_max_depth";
    static constexpr auto inputType = common::LogicalTypeID::INT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct SparseFrontierThresholdSetting {
    static constexpr auto name = "sparse_frontier_threshold";
    static constexpr auto inputType = common::LogicalTypeID::INT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct EnableSemiMaskSetting {
    static constexpr auto name = "enable_semi_mask";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct DisableMapKeyCheck {
    static constexpr auto name = "disable_map_key_check";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct EnableZoneMapSetting {
    static constexpr auto name = "enable_zone_map";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct HomeDirectorySetting {
    static constexpr auto name = "home_directory";
    static constexpr auto inputType = common::LogicalTypeID::STRING;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct FileSearchPathSetting {
    static constexpr auto name = "file_search_path";
    static constexpr auto inputType = common::LogicalTypeID::STRING;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct RecursivePatternSemanticSetting {
    static constexpr auto name = "recursive_pattern_semantic";
    static constexpr auto inputType = common::LogicalTypeID::STRING;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct RecursivePatternFactorSetting {
    static constexpr auto name = "recursive_pattern_factor";
    static constexpr auto inputType = common::LogicalTypeID::INT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct EnableMVCCSetting {
    static constexpr auto name = "debug_enable_multi_writes";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct CheckpointThresholdSetting {
    static constexpr auto name = "checkpoint_threshold";
    static constexpr auto inputType = common::LogicalTypeID::INT64;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct AutoCheckpointSetting {
    static constexpr auto name = "auto_checkpoint";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct ForceCheckpointClosingDBSetting {
    static constexpr auto name = "force_checkpoint_on_close";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct SpillToDiskSetting {
    static constexpr auto name = "spill_to_disk";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct EnableOptimizerSetting {
    static constexpr auto name = "enable_plan_optimizer";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

struct EnableInternalCatalogSetting {
    static constexpr auto name = "enable_internal_catalog";
    static constexpr auto inputType = common::LogicalTypeID::BOOL;
    static void setContext(ClientContext* context, const common::Value& parameter);
    static common::Value getSetting(const ClientContext* context);
};

} // namespace main
} // namespace lbug
