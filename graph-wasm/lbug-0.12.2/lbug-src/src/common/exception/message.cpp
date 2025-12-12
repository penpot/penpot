#include "common/exception/message.h"

#include "common/string_format.h"

namespace lbug {
namespace common {

std::string ExceptionMessage::duplicatePKException(const std::string& pkString) {
    return stringFormat("Found duplicated primary key value {}, which violates the uniqueness"
                        " constraint of the primary key column.",
        pkString);
}

std::string ExceptionMessage::nonExistentPKException(const std::string& pkString) {
    return stringFormat("Unable to find primary key value {}.", pkString);
}

std::string ExceptionMessage::invalidPKType(const std::string& type) {
    return stringFormat("Invalid primary key column type {}. Primary keys must be either STRING or "
                        "a numeric type.",
        type);
}

std::string ExceptionMessage::nullPKException() {
    return "Found NULL, which violates the non-null constraint of the primary key column.";
}

std::string ExceptionMessage::overLargeStringPKValueException(uint64_t length) {
    return stringFormat("The maximum length of primary key strings is 262144 bytes. The input "
                        "string's length was {}.",
        length);
}

std::string ExceptionMessage::overLargeStringValueException(uint64_t length) {
    return stringFormat(
        "The maximum length of strings is 262144 bytes. The input string's length was {}.", length);
}

std::string ExceptionMessage::violateDeleteNodeWithConnectedEdgesConstraint(
    const std::string& tableName, const std::string& offset, const std::string& direction) {
    return stringFormat(
        "Node(nodeOffset: {}) has connected edges in table {} in the {} direction, "
        "which cannot be deleted. Please delete the edges first or try DETACH DELETE.",
        offset, tableName, direction);
}

std::string ExceptionMessage::violateRelMultiplicityConstraint(const std::string& tableName,
    const std::string& offset, const std::string& direction) {
    return stringFormat("Node(nodeOffset: {}) has more than one neighbour in table {} in the {} "
                        "direction, which violates the rel multiplicity constraint.",
        offset, tableName, direction);
}

std::string ExceptionMessage::variableNotInScope(const std::string& varName) {
    return stringFormat("Variable {} is not in scope.", varName);
}

std::string ExceptionMessage::listFunctionIncompatibleChildrenType(const std::string& functionName,
    const std::string& leftType, const std::string& rightType) {
    return std::string("Cannot bind " + functionName + " with parameter type " + leftType +
                       " and " + rightType + ".");
}

std::string ExceptionMessage::invalidSkipLimitParam(const std::string& exprName,
    const std::string& skipOrLimit) {
    return stringFormat("Cannot evaluate {} as a valid {} number.", exprName, skipOrLimit);
}

} // namespace common
} // namespace lbug
