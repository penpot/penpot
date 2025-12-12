#pragma once

#include <string>

#include "common/copy_constructors.h"
#include "common/enums/alter_type.h"
#include "common/enums/conflict_action.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

struct ExtraAlterInfo {
    virtual ~ExtraAlterInfo() = default;

    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

struct AlterInfo {
    common::AlterType type;
    std::string tableName;
    std::unique_ptr<ExtraAlterInfo> extraInfo;
    common::ConflictAction onConflict;

    AlterInfo(common::AlterType type, std::string tableName,
        std::unique_ptr<ExtraAlterInfo> extraInfo,
        common::ConflictAction onConflict = common::ConflictAction::ON_CONFLICT_THROW)
        : type{type}, tableName{std::move(tableName)}, extraInfo{std::move(extraInfo)},
          onConflict{onConflict} {}
    DELETE_COPY_DEFAULT_MOVE(AlterInfo);
};

struct ExtraRenameTableInfo : public ExtraAlterInfo {
    std::string newName;

    explicit ExtraRenameTableInfo(std::string newName) : newName{std::move(newName)} {}
};

struct ExtraAddFromToConnection : public ExtraAlterInfo {
    std::string srcTableName;
    std::string dstTableName;

    explicit ExtraAddFromToConnection(std::string srcTableName, std::string dstTableName)
        : srcTableName{std::move(srcTableName)}, dstTableName{std::move(dstTableName)} {}
};

struct ExtraAddPropertyInfo : public ExtraAlterInfo {
    std::string propertyName;
    std::string dataType;
    std::unique_ptr<ParsedExpression> defaultValue;

    ExtraAddPropertyInfo(std::string propertyName, std::string dataType,
        std::unique_ptr<ParsedExpression> defaultValue)
        : propertyName{std::move(propertyName)}, dataType{std::move(dataType)},
          defaultValue{std::move(defaultValue)} {}
};

struct ExtraDropPropertyInfo : public ExtraAlterInfo {
    std::string propertyName;

    explicit ExtraDropPropertyInfo(std::string propertyName)
        : propertyName{std::move(propertyName)} {}
};

struct ExtraRenamePropertyInfo : public ExtraAlterInfo {
    std::string propertyName;
    std::string newName;

    ExtraRenamePropertyInfo(std::string propertyName, std::string newName)
        : propertyName{std::move(propertyName)}, newName{std::move(newName)} {}
};

struct ExtraCommentInfo : public ExtraAlterInfo {
    std::string comment;

    explicit ExtraCommentInfo(std::string comment) : comment{std::move(comment)} {}
};

} // namespace parser
} // namespace lbug
