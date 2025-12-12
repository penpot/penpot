#include "binder/expression/expression_util.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "function/binary_function_executor.h"
#include "function/list/functions/list_extract_function.h"
#include "function/rewrite_function.h"
#include "function/scalar_function.h"
#include "function/schema/vector_node_rel_functions.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::catalog;

namespace lbug {
namespace function {

struct Label {
    static void operation(internalID_t& left, list_entry_t& right, ku_string_t& result,
        ValueVector& leftVector, ValueVector& rightVector, ValueVector& resultVector,
        uint64_t resPos) {
        KU_ASSERT(left.tableID < right.size);
        ListExtract::operation(right, left.tableID + 1 /* listExtract requires 1-based index */,
            result, rightVector, leftVector, resultVector, resPos);
    }
};

static void execFunction(const std::vector<std::shared_ptr<ValueVector>>& params,
    const std::vector<SelectionVector*>& paramSelVectors, ValueVector& result,
    SelectionVector* resultSelVector, void* dataPtr = nullptr) {
    KU_ASSERT(params.size() == 2);
    BinaryFunctionExecutor::executeSwitch<internalID_t, list_entry_t, ku_string_t, Label,
        BinaryListExtractFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
        paramSelVectors[1], result, resultSelVector, dataPtr);
}

static std::shared_ptr<Expression> getLabelsAsLiteral(
    std::unordered_map<table_id_t, std::string> map, ExpressionBinder* expressionBinder) {
    table_id_t maxTableID = 0;
    for (auto [id, name] : map) {
        if (id > maxTableID) {
            maxTableID = id;
        }
    }
    std::vector<std::unique_ptr<Value>> labels;
    labels.resize(maxTableID + 1);
    for (auto i = 0u; i < labels.size(); ++i) {
        if (map.contains(i)) {
            labels[i] = std::make_unique<Value>(LogicalType::STRING(), map.at(i));
        } else {
            labels[i] = std::make_unique<Value>(LogicalType::STRING(), std::string(""));
        }
    }
    auto labelsValue = Value(LogicalType::LIST(LogicalType::STRING()), std::move(labels));
    return expressionBinder->createLiteralExpression(labelsValue);
}

static std::unordered_map<table_id_t, std::string> getNodeTableIDToLabel(
    std::vector<TableCatalogEntry*> entries) {
    std::unordered_map<table_id_t, std::string> map;
    for (auto& entry : entries) {
        map.insert({entry->getTableID(), entry->getName()});
    }
    return map;
}

static std::unordered_map<table_id_t, std::string> getRelTableIDToLabel(
    std::vector<TableCatalogEntry*> entries) {
    std::unordered_map<table_id_t, std::string> map;
    for (auto& entry : entries) {
        auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
        for (auto& relEntryInfo : relGroupEntry.getRelEntryInfos()) {
            map.insert({relEntryInfo.oid, entry->getName()});
        }
    }
    return map;
}

std::shared_ptr<Expression> LabelFunction::rewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto argument = input.arguments[0].get();
    auto expressionBinder = input.expressionBinder;
    if (ExpressionUtil::isNullLiteral(*argument)) {
        return expressionBinder->createNullLiteralExpression();
    }
    expression_vector children;
    if (argument->expressionType == ExpressionType::VARIABLE) {
        children.push_back(input.arguments[0]);
        children.push_back(expressionBinder->createLiteralExpression(InternalKeyword::LABEL));
        return expressionBinder->bindScalarFunctionExpression(children,
            StructExtractFunctions::name);
    }
    auto disableLiteralRewrite = expressionBinder->getConfig().disableLabelFunctionLiteralRewrite;
    if (ExpressionUtil::isNodePattern(*argument)) {
        auto& node = argument->constCast<NodeExpression>();
        if (!disableLiteralRewrite) {
            if (node.isEmpty()) {
                return expressionBinder->createLiteralExpression("");
            }
            if (!node.isMultiLabeled()) {
                auto label = node.getEntry(0)->getName();
                return expressionBinder->createLiteralExpression(label);
            }
        }
        children.push_back(node.getInternalID());
        auto map = getNodeTableIDToLabel(node.getEntries());
        children.push_back(getLabelsAsLiteral(map, expressionBinder));
    } else if (ExpressionUtil::isRelPattern(*argument)) {
        auto& rel = argument->constCast<RelExpression>();
        if (!disableLiteralRewrite) {
            if (rel.isEmpty()) {
                return expressionBinder->createLiteralExpression("");
            }
            if (!rel.isMultiLabeled()) {
                auto label = rel.getEntry(0)->getName();
                return expressionBinder->createLiteralExpression(label);
            }
        }
        children.push_back(rel.getInternalID());
        auto map = getRelTableIDToLabel(rel.getEntries());
        children.push_back(getLabelsAsLiteral(map, expressionBinder));
    }
    KU_ASSERT(children.size() == 2);
    auto function = std::make_unique<ScalarFunction>(LabelFunction::name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::INT64},
        LogicalTypeID::STRING, execFunction);
    auto bindData = std::make_unique<function::FunctionBindData>(LogicalType::STRING());
    auto uniqueName = ScalarFunctionExpression::getUniqueName(LabelFunction::name, children);
    return std::make_shared<ScalarFunctionExpression>(ExpressionType::FUNCTION, std::move(function),
        std::move(bindData), std::move(children), uniqueName);
}

function_set LabelFunction::getFunctionSet() {
    function_set set;
    auto inputTypes =
        std::vector<LogicalTypeID>{LogicalTypeID::NODE, LogicalTypeID::REL, LogicalTypeID::STRUCT};
    for (auto& inputType : inputTypes) {
        auto function = std::make_unique<RewriteFunction>(name,
            std::vector<LogicalTypeID>{inputType}, rewriteFunc);
        set.push_back(std::move(function));
    }
    return set;
}

} // namespace function
} // namespace lbug
