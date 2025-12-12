#pragma once

#include "binder/expression/expression.h"
#include "common/api.h"

namespace lbug {

namespace main {
class ClientContext;
}

namespace function {

struct LBUG_API FunctionBindData {
    std::vector<common::LogicalType> paramTypes;
    common::LogicalType resultType;
    // TODO: the following two fields should be moved to FunctionLocalState.
    main::ClientContext* clientContext;
    int64_t count;

    explicit FunctionBindData(common::LogicalType dataType)
        : resultType{std::move(dataType)}, clientContext{nullptr}, count{1} {}
    FunctionBindData(std::vector<common::LogicalType> paramTypes, common::LogicalType resultType)
        : paramTypes{std::move(paramTypes)}, resultType{std::move(resultType)},
          clientContext{nullptr}, count{1} {}
    DELETE_COPY_AND_MOVE(FunctionBindData);
    virtual ~FunctionBindData() = default;

    static std::unique_ptr<FunctionBindData> getSimpleBindData(
        const binder::expression_vector& params, const common::LogicalType& resultType);

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

    virtual std::unique_ptr<FunctionBindData> copy() const {
        return std::make_unique<FunctionBindData>(common::LogicalType::copy(paramTypes),
            resultType.copy());
    }
};

struct Function;
using function_set = std::vector<std::unique_ptr<Function>>;

struct ScalarBindFuncInput {
    const binder::expression_vector& arguments;
    Function* definition;
    main::ClientContext* context;
    std::vector<std::string> optionalArguments;

    ScalarBindFuncInput(const binder::expression_vector& arguments, Function* definition,
        main::ClientContext* context, std::vector<std::string> optionalArguments)
        : arguments{arguments}, definition{definition}, context{context},
          optionalArguments{std::move(optionalArguments)} {}
};

using scalar_bind_func =
    std::function<std::unique_ptr<FunctionBindData>(const ScalarBindFuncInput& bindInput)>;

struct LBUG_API Function {
    std::string name;
    std::vector<common::LogicalTypeID> parameterTypeIDs;
    bool isReadOnly = true;

    Function() : isReadOnly{true} {};
    Function(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs)
        : name{std::move(name)}, parameterTypeIDs{std::move(parameterTypeIDs)} {}
    Function(const Function&) = default;

    virtual ~Function() = default;

    virtual std::string signatureToString() const {
        return common::LogicalTypeUtils::toString(parameterTypeIDs);
    }

    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

struct ScalarOrAggregateFunction : Function {
    common::LogicalTypeID returnTypeID = common::LogicalTypeID::ANY;
    scalar_bind_func bindFunc = nullptr;

    ScalarOrAggregateFunction() : Function{} {}
    ScalarOrAggregateFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        common::LogicalTypeID returnTypeID)
        : Function{std::move(name), std::move(parameterTypeIDs)}, returnTypeID{returnTypeID} {}
    ScalarOrAggregateFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        common::LogicalTypeID returnTypeID, scalar_bind_func bindFunc)
        : Function{std::move(name), std::move(parameterTypeIDs)}, returnTypeID{returnTypeID},
          bindFunc{std::move(bindFunc)} {}

    std::string signatureToString() const override {
        auto result = Function::signatureToString();
        result += " -> " + common::LogicalTypeUtils::toString(returnTypeID);
        return result;
    }
};

} // namespace function
} // namespace lbug
