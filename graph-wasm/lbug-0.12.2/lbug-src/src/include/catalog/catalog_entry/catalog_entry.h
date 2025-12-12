#pragma once

#include <string>

#include "catalog_entry_type.h"
#include "common/assert.h"
#include "common/copy_constructors.h"
#include "common/serializer/serializer.h"
#include "common/types/types.h"

namespace lbug {
namespace main {
class ClientContext;
} // namespace main

namespace catalog {

struct LBUG_API ToCypherInfo {
    virtual ~ToCypherInfo() = default;

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

class LBUG_API CatalogEntry {
public:
    //===--------------------------------------------------------------------===//
    // constructor & destructor
    //===--------------------------------------------------------------------===//
    CatalogEntry() : CatalogEntry{CatalogEntryType::DUMMY_ENTRY, ""} {}
    CatalogEntry(CatalogEntryType type, std::string name)
        : type{type}, name{std::move(name)}, oid{common::INVALID_OID},
          timestamp{common::INVALID_TRANSACTION} {}
    DELETE_COPY_DEFAULT_MOVE(CatalogEntry);
    virtual ~CatalogEntry() = default;

    //===--------------------------------------------------------------------===//
    // getter & setter
    //===--------------------------------------------------------------------===//
    CatalogEntryType getType() const { return type; }
    void rename(std::string name_) { this->name = std::move(name_); }
    std::string getName() const { return name; }
    common::transaction_t getTimestamp() const { return timestamp; }
    void setTimestamp(common::transaction_t timestamp_) { this->timestamp = timestamp_; }
    bool isDeleted() const { return deleted; }
    void setDeleted(bool deleted_) { this->deleted = deleted_; }
    bool hasParent() const { return hasParent_; }
    void setHasParent(bool hasParent) { hasParent_ = hasParent; }
    void setOID(common::oid_t oid) { this->oid = oid; }
    common::oid_t getOID() const { return oid; }
    CatalogEntry* getPrev() const {
        KU_ASSERT(prev);
        return prev.get();
    }
    std::unique_ptr<CatalogEntry> movePrev() {
        if (this->prev) {
            this->prev->setNext(nullptr);
        }
        return std::move(prev);
    }
    void setPrev(std::unique_ptr<CatalogEntry> prev_) {
        this->prev = std::move(prev_);
        if (this->prev) {
            this->prev->setNext(this);
        }
    }
    CatalogEntry* getNext() const { return next; }
    void setNext(CatalogEntry* next_) { this->next = next_; }

    //===--------------------------------------------------------------------===//
    // serialization & deserialization
    //===--------------------------------------------------------------------===//
    virtual void serialize(common::Serializer& serializer) const;
    static std::unique_ptr<CatalogEntry> deserialize(common::Deserializer& deserializer);

    virtual std::string toCypher(const ToCypherInfo& /*info*/) const { KU_UNREACHABLE; }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }
    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

protected:
    virtual void copyFrom(const CatalogEntry& other);

protected:
    CatalogEntryType type;
    std::string name;
    common::oid_t oid;
    common::transaction_t timestamp;
    bool deleted = false;
    bool hasParent_ = false;
    // Older versions.
    std::unique_ptr<CatalogEntry> prev;
    // Newer versions.
    CatalogEntry* next = nullptr;
};

} // namespace catalog
} // namespace lbug
