#pragma once

#include "processor/data_pos.h"
#include "processor/result/factorized_table_schema.h"

namespace lbug {
namespace processor {

struct OrderByDataInfo {
    std::vector<DataPos> keysPos;
    std::vector<DataPos> payloadsPos;
    std::vector<common::LogicalType> keyTypes;
    std::vector<common::LogicalType> payloadTypes;
    std::vector<bool> isAscOrder;
    FactorizedTableSchema payloadTableSchema;
    std::vector<uint32_t> keyInPayloadPos;

    OrderByDataInfo(std::vector<DataPos> keysPos, std::vector<DataPos> payloadsPos,
        std::vector<common::LogicalType> keyTypes, std::vector<common::LogicalType> payloadTypes,
        std::vector<bool> isAscOrder, FactorizedTableSchema payloadTableSchema,
        std::vector<uint32_t> keyInPayloadPos)
        : keysPos{std::move(keysPos)}, payloadsPos{std::move(payloadsPos)},
          keyTypes{std::move(keyTypes)}, payloadTypes{std::move(payloadTypes)},
          isAscOrder{std::move(isAscOrder)}, payloadTableSchema{std::move(payloadTableSchema)},
          keyInPayloadPos{std::move(keyInPayloadPos)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(OrderByDataInfo);

private:
    OrderByDataInfo(const OrderByDataInfo& other)
        : keysPos{other.keysPos}, payloadsPos{other.payloadsPos},
          keyTypes{common::LogicalType::copy(other.keyTypes)},
          payloadTypes{common::LogicalType::copy(other.payloadTypes)}, isAscOrder{other.isAscOrder},
          payloadTableSchema{other.payloadTableSchema.copy()},
          keyInPayloadPos{other.keyInPayloadPos} {}
};

} // namespace processor
} // namespace lbug
