#include "common/arrow/arrow_converter.h"
#include "common/exception/not_implemented.h"
#include "common/string_utils.h"

namespace lbug {
namespace common {

// Pyarrow format string specifications can be found here
// https://arrow.apache.org/docs/format/CDataInterface.html#data-type-description-format-strings

LogicalType ArrowConverter::fromArrowSchema(const ArrowSchema* schema) {
    const char* arrowType = schema->format;
    std::vector<StructField> structFields;
    // If we have a dictionary, then the logical type of the column is dependent upon the
    // logical type of the dict
    if (schema->dictionary != nullptr) {
        return fromArrowSchema(schema->dictionary);
    }
    switch (arrowType[0]) {
    case 'n':
        return LogicalType(LogicalTypeID::ANY);
    case 'b':
        return LogicalType(LogicalTypeID::BOOL);
    case 'c':
        return LogicalType(LogicalTypeID::INT8);
    case 'C':
        return LogicalType(LogicalTypeID::UINT8);
    case 's':
        return LogicalType(LogicalTypeID::INT16);
    case 'S':
        return LogicalType(LogicalTypeID::UINT16);
    case 'i':
        return LogicalType(LogicalTypeID::INT32);
    case 'I':
        return LogicalType(LogicalTypeID::UINT32);
    case 'l':
        return LogicalType(LogicalTypeID::INT64);
    case 'L':
        return LogicalType(LogicalTypeID::UINT64);
    case 'e':
        throw NotImplementedException("16 bit floats are not supported");
    case 'f':
        return LogicalType(LogicalTypeID::FLOAT);
    case 'g':
        return LogicalType(LogicalTypeID::DOUBLE);
    case 'z':
    case 'Z':
        return LogicalType(LogicalTypeID::BLOB);
    case 'u':
    case 'U':
        return LogicalType(LogicalTypeID::STRING);
    case 'v':
        switch (arrowType[1]) {
        case 'z':
            return LogicalType(LogicalTypeID::BLOB);
        case 'u':
            return LogicalType(LogicalTypeID::STRING);
        default:
            KU_UNREACHABLE;
        }

    case 'd': {
        auto split = StringUtils::splitComma(std::string(arrowType + 2));
        if (split.size() > 2 && split[2] != "128") {
            throw NotImplementedException("Decimal bitwidths other than 128 are not implemented");
        }
        return LogicalType::DECIMAL(stoul(split[0]), stoul(split[1]));
    }
    case 'w':
        return LogicalType(LogicalTypeID::BLOB); // fixed width binary
    case 't':
        switch (arrowType[1]) {
        case 'd':
            if (arrowType[2] == 'D') {
                return LogicalType(LogicalTypeID::DATE);
            } else {
                return LogicalType(LogicalTypeID::TIMESTAMP_MS);
            }
        case 't':
            // TODO implement pure time type
            throw NotImplementedException("Pure time types are not supported");
        case 's':
            // TODO maxwell: timezone support
            switch (arrowType[2]) {
            case 's':
                return LogicalType(LogicalTypeID::TIMESTAMP_SEC);
            case 'm':
                return LogicalType(LogicalTypeID::TIMESTAMP_MS);
            case 'u':
                return LogicalType(LogicalTypeID::TIMESTAMP);
            case 'n':
                return LogicalType(LogicalTypeID::TIMESTAMP_NS);
            default:
                KU_UNREACHABLE;
            }
        case 'D':
            // duration
        case 'i':
            // interval
            return LogicalType(LogicalTypeID::INTERVAL);
        default:
            KU_UNREACHABLE;
        }
    case '+':
        KU_ASSERT(schema->n_children > 0);
        switch (arrowType[1]) {
        // complex types need a complementary ExtraTypeInfo object
        case 'l':
        case 'L':
            return LogicalType::LIST(LogicalType(fromArrowSchema(schema->children[0])));
        case 'w':
            return LogicalType::ARRAY(LogicalType(fromArrowSchema(schema->children[0])),
                std::stoul(arrowType + 3));
        case 's':
            for (int64_t i = 0; i < schema->n_children; i++) {
                structFields.emplace_back(std::string(schema->children[i]->name),
                    LogicalType(fromArrowSchema(schema->children[i])));
            }
            return LogicalType::STRUCT(std::move(structFields));
        case 'm':
            return LogicalType::MAP(LogicalType(fromArrowSchema(schema->children[0]->children[0])),
                LogicalType(fromArrowSchema(schema->children[0]->children[1])));
        case 'u': {
            for (int64_t i = 0; i < schema->n_children; i++) {
                structFields.emplace_back(std::to_string(i),
                    LogicalType(fromArrowSchema(schema->children[i])));
            }
            return LogicalType::UNION(std::move(structFields));
        }
        case 'v':
            switch (arrowType[2]) {
            case 'l':
            case 'L':
                return LogicalType::LIST(LogicalType(fromArrowSchema(schema->children[0])));
            default:
                KU_UNREACHABLE;
            }
        case 'r':
            // logical type corresponds to second child
            return fromArrowSchema(schema->children[1]);
        default:
            KU_UNREACHABLE;
        }
    default:
        KU_UNREACHABLE;
    }
    // refer to arrow_converted.cpp:65
}

} // namespace common
} // namespace lbug
