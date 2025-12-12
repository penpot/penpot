#pragma once

#include "binder/ddl/property_definition.h"
#include "common/case_insensitive_map.h"

namespace lbug {
namespace catalog {

class LBUG_API PropertyDefinitionCollection {
public:
    PropertyDefinitionCollection() : nextColumnID{0}, nextPropertyID{0} {}
    explicit PropertyDefinitionCollection(common::column_id_t nextColumnID)
        : nextColumnID{nextColumnID}, nextPropertyID{0} {}
    EXPLICIT_COPY_DEFAULT_MOVE(PropertyDefinitionCollection);

    common::idx_t size() const { return definitions.size(); }

    bool contains(const std::string& name) const { return nameToPropertyIDMap.contains(name); }

    std::vector<binder::PropertyDefinition> getDefinitions() const;
    const binder::PropertyDefinition& getDefinition(const std::string& name) const;
    const binder::PropertyDefinition& getDefinition(common::idx_t idx) const;
    common::column_id_t getMaxColumnID() const;
    common::column_id_t getColumnID(const std::string& name) const;
    common::column_id_t getColumnID(common::property_id_t propertyID) const;
    common::property_id_t getPropertyID(const std::string& name) const;
    void vacuumColumnIDs(common::column_id_t nextColumnID);

    void add(const binder::PropertyDefinition& definition);
    void drop(const std::string& name);
    void rename(const std::string& name, const std::string& newName);

    std::string toCypher() const;

    void serialize(common::Serializer& serializer) const;
    static PropertyDefinitionCollection deserialize(common::Deserializer& deserializer);

private:
    PropertyDefinitionCollection(const PropertyDefinitionCollection& other)
        : nextColumnID{other.nextColumnID}, nextPropertyID{other.nextPropertyID},
          definitions{copyMap(other.definitions)}, columnIDs{other.columnIDs},
          nameToPropertyIDMap{other.nameToPropertyIDMap} {}

private:
    common::column_id_t nextColumnID;
    common::property_id_t nextPropertyID;
    std::map<common::property_id_t, binder::PropertyDefinition> definitions;
    std::unordered_map<common::property_id_t, common::column_id_t> columnIDs;
    common::case_insensitive_map_t<common::property_id_t> nameToPropertyIDMap;
};

} // namespace catalog
} // namespace lbug
