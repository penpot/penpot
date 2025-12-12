#pragma once

#include "binder/expression/expression_util.h"

namespace lbug {
namespace function {

template<common::LogicalTypeID ID>
struct LogicalTypeMapping;

template<>
struct LogicalTypeMapping<common::LogicalTypeID::DOUBLE> {
    using type = double;
};
template<>
struct LogicalTypeMapping<common::LogicalTypeID::BOOL> {
    using type = bool;
};
template<>
struct LogicalTypeMapping<common::LogicalTypeID::UINT64> {
    using type = uint64_t;
};
template<>
struct LogicalTypeMapping<common::LogicalTypeID::INT64> {
    using type = int64_t;
};

template<>
struct LogicalTypeMapping<common::LogicalTypeID::STRING> {
    using type = std::string;
};

template<typename PARAM>
struct OptionalParam {
    using T = typename LogicalTypeMapping<PARAM::TYPE>::type;
    std::shared_ptr<binder::Expression> param = nullptr;
    T paramVal = PARAM::DEFAULT_VALUE;

    OptionalParam() {}

    explicit OptionalParam(std::shared_ptr<binder::Expression> param) : param{std::move(param)} {}

    void evaluateParam(main::ClientContext* context) {
        if (!param) {
            paramVal = PARAM::DEFAULT_VALUE;
            return;
        }
        if constexpr (requires { PARAM::validate; }) {
            paramVal = binder::ExpressionUtil::evaluateLiteral<T>(context, param,
                common::LogicalType{PARAM::TYPE}, PARAM::validate);
        } else {
            paramVal = binder::ExpressionUtil::evaluateLiteral<T>(context, param,
                common::LogicalType{PARAM::TYPE}, nullptr /* validateFunc */);
        }
    }

    bool isSet() const { return param != nullptr; }

    T getParamVal() const { return paramVal; }
};

struct OptionalParams {
    virtual ~OptionalParams() = default;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

    virtual void evaluateParams(main::ClientContext* /*context*/) = 0;

    virtual std::unique_ptr<OptionalParams> copy() = 0;
};

} // namespace function
} // namespace lbug
