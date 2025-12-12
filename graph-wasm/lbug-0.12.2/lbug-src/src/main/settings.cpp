#include "main/settings.h"

#include "common/exception/runtime.h"
#include "common/task_system/progress_bar.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/storage_utils.h"

namespace lbug {
namespace main {

void ThreadsSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->numThreads = parameter.getValue<uint64_t>();
}

common::Value ThreadsSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->numThreads);
}

void WarningLimitSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->warningLimit = parameter.getValue<uint64_t>();
}

common::Value WarningLimitSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->warningLimit);
}

void TimeoutSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->timeoutInMS = parameter.getValue<uint64_t>();
}

common::Value TimeoutSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->timeoutInMS);
}

void ProgressBarSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->enableProgressBar = parameter.getValue<bool>();
    common::ProgressBar::Get(*context)->toggleProgressBarPrinting(parameter.getValue<bool>());
}

common::Value ProgressBarSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->enableProgressBar);
}

void VarLengthExtendMaxDepthSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->varLengthMaxDepth = parameter.getValue<int64_t>();
}

common::Value VarLengthExtendMaxDepthSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->varLengthMaxDepth);
}

void SparseFrontierThresholdSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->sparseFrontierThreshold = parameter.getValue<int64_t>();
}

common::Value SparseFrontierThresholdSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->sparseFrontierThreshold);
}

void EnableSemiMaskSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->enableSemiMask = parameter.getValue<bool>();
}

common::Value EnableSemiMaskSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->enableSemiMask);
}

void DisableMapKeyCheck::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->disableMapKeyCheck = parameter.getValue<bool>();
}

common::Value DisableMapKeyCheck::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->disableMapKeyCheck);
}

void EnableZoneMapSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->enableZoneMap = parameter.getValue<bool>();
}

common::Value EnableZoneMapSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getClientConfig()->enableZoneMap);
}

void HomeDirectorySetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->homeDirectory = parameter.getValue<std::string>();
}

common::Value HomeDirectorySetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(context->getClientConfig()->homeDirectory);
}

void FileSearchPathSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->fileSearchPath = parameter.getValue<std::string>();
}

common::Value FileSearchPathSetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(context->getClientConfig()->fileSearchPath);
}

void RecursivePatternSemanticSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    const auto input = parameter.getValue<std::string>();
    context->getClientConfigUnsafe()->recursivePatternSemantic =
        common::PathSemanticUtils::fromString(input);
}

common::Value RecursivePatternSemanticSetting::getSetting(const ClientContext* context) {
    const auto result =
        common::PathSemanticUtils::toString(context->getClientConfig()->recursivePatternSemantic);
    return common::Value::createValue(result);
}

void RecursivePatternFactorSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->recursivePatternCardinalityScaleFactor =
        parameter.getValue<std::int64_t>();
}

common::Value RecursivePatternFactorSetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(
        context->getClientConfig()->recursivePatternCardinalityScaleFactor);
}

void EnableMVCCSetting::setContext(ClientContext* context, const common::Value& parameter) {
    KU_ASSERT(parameter.getDataType().getLogicalTypeID() == common::LogicalTypeID::BOOL);
    // TODO: This is a temporary solution to make tests of multiple write transactions easier.
    context->getDBConfigUnsafe()->enableMultiWrites = parameter.getValue<bool>();
}

common::Value EnableMVCCSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getDBConfig()->enableMultiWrites);
}

void CheckpointThresholdSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getDBConfigUnsafe()->checkpointThreshold = parameter.getValue<int64_t>();
}

common::Value CheckpointThresholdSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getDBConfig()->checkpointThreshold);
}

void AutoCheckpointSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getDBConfigUnsafe()->autoCheckpoint = parameter.getValue<bool>();
}

common::Value AutoCheckpointSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getDBConfig()->autoCheckpoint);
}

void ForceCheckpointClosingDBSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getDBConfigUnsafe()->forceCheckpointOnClose = parameter.getValue<bool>();
}

common::Value ForceCheckpointClosingDBSetting::getSetting(const ClientContext* context) {
    return common::Value(context->getDBConfig()->forceCheckpointOnClose);
}

void EnableOptimizerSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->enablePlanOptimizer = parameter.getValue<bool>();
}

common::Value EnableOptimizerSetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(context->getClientConfig()->enablePlanOptimizer);
}

void EnableInternalCatalogSetting::setContext(ClientContext* context,
    const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getClientConfigUnsafe()->enableInternalCatalog = parameter.getValue<bool>();
}

common::Value EnableInternalCatalogSetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(context->getClientConfig()->enableInternalCatalog);
}

void SpillToDiskSetting::setContext(ClientContext* context, const common::Value& parameter) {
    parameter.validateType(inputType);
    context->getDBConfigUnsafe()->enableSpillingToDisk = parameter.getValue<bool>();
    const auto& dbConfig = *context->getDBConfig();
    std::string spillPath;
    if (dbConfig.enableSpillingToDisk) {
        if (context->isInMemory()) {
            throw common::RuntimeException(
                "Cannot set spill_to_disk to true for an in-memory database!");
        }
        if (!context->canExecuteWriteQuery()) {
            throw common::RuntimeException(
                "Cannot set spill_to_disk to true for a read only database!");
        }
        spillPath = storage::StorageUtils::getTmpFilePath(context->getDatabasePath());
    } else {
        // Set path to empty will disable spiller.
        spillPath = "";
    }
    storage::MemoryManager::Get(*context)->getBufferManager()->resetSpiller(spillPath);
}

common::Value SpillToDiskSetting::getSetting(const ClientContext* context) {
    return common::Value::createValue(context->getDBConfig()->enableSpillingToDisk);
}

} // namespace main
} // namespace lbug
