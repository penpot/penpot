#pragma once

#include "binder/ddl/property_definition.h"
#include "binder/expression/expression.h"
#include "common/enums/alter_type.h"
#include "common/enums/conflict_action.h"

namespace lbug {
namespace binder {

struct BoundExtraAlterInfo {
    virtual ~BoundExtraAlterInfo() = default;

    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

    virtual std::unique_ptr<BoundExtraAlterInfo> copy() const = 0;
};

struct BoundAlterInfo {
    common::AlterType alterType;
    std::string tableName;
    std::unique_ptr<BoundExtraAlterInfo> extraInfo;
    common::ConflictAction onConflict;

    BoundAlterInfo(common::AlterType alterType, std::string tableName,
        std::unique_ptr<BoundExtraAlterInfo> extraInfo,
        common::ConflictAction onConflict = common::ConflictAction::ON_CONFLICT_THROW)
        : alterType{alterType}, tableName{std::move(tableName)}, extraInfo{std::move(extraInfo)},
          onConflict{onConflict} {}
    EXPLICIT_COPY_DEFAULT_MOVE(BoundAlterInfo);

    std::string toString() const;

private:
    BoundAlterInfo(const BoundAlterInfo& other)
        : alterType{other.alterType}, tableName{other.tableName},
          extraInfo{other.extraInfo->copy()}, onConflict{other.onConflict} {}
};

struct BoundExtraRenameTableInfo final : BoundExtraAlterInfo {
    std::string newName;

    explicit BoundExtraRenameTableInfo(std::string newName) : newName{std::move(newName)} {}
    BoundExtraRenameTableInfo(const BoundExtraRenameTableInfo& other) : newName{other.newName} {}

    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraRenameTableInfo>(*this);
    }
};

struct BoundExtraAddPropertyInfo final : BoundExtraAlterInfo {
    PropertyDefinition propertyDefinition;
    std::shared_ptr<Expression> boundDefault;

    BoundExtraAddPropertyInfo(const PropertyDefinition& definition,
        std::shared_ptr<Expression> boundDefault)
        : propertyDefinition{definition.copy()}, boundDefault{std::move(boundDefault)} {}
    BoundExtraAddPropertyInfo(const BoundExtraAddPropertyInfo& other)
        : propertyDefinition{other.propertyDefinition.copy()}, boundDefault{other.boundDefault} {}

    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraAddPropertyInfo>(*this);
    }
};

struct BoundExtraDropPropertyInfo final : BoundExtraAlterInfo {
    std::string propertyName;

    explicit BoundExtraDropPropertyInfo(std::string propertyName)
        : propertyName{std::move(propertyName)} {}
    BoundExtraDropPropertyInfo(const BoundExtraDropPropertyInfo& other)
        : propertyName{other.propertyName} {}

    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraDropPropertyInfo>(*this);
    }
};

struct BoundExtraRenamePropertyInfo final : BoundExtraAlterInfo {
    std::string newName;
    std::string oldName;

    BoundExtraRenamePropertyInfo(std::string newName, std::string oldName)
        : newName{std::move(newName)}, oldName{std::move(oldName)} {}
    BoundExtraRenamePropertyInfo(const BoundExtraRenamePropertyInfo& other)
        : newName{other.newName}, oldName{other.oldName} {}
    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraRenamePropertyInfo>(*this);
    }
};

struct BoundExtraCommentInfo final : BoundExtraAlterInfo {
    std::string comment;

    explicit BoundExtraCommentInfo(std::string comment) : comment{std::move(comment)} {}
    BoundExtraCommentInfo(const BoundExtraCommentInfo& other) : comment{other.comment} {}
    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraCommentInfo>(*this);
    }
};

struct BoundExtraAlterFromToConnection final : BoundExtraAlterInfo {
    common::table_id_t fromTableID;
    common::table_id_t toTableID;

    BoundExtraAlterFromToConnection(common::table_id_t fromTableID, common::table_id_t toTableID)
        : fromTableID{fromTableID}, toTableID{toTableID} {}
    std::unique_ptr<BoundExtraAlterInfo> copy() const override {
        return std::make_unique<BoundExtraAlterFromToConnection>(*this);
    }
};

} // namespace binder
} // namespace lbug
