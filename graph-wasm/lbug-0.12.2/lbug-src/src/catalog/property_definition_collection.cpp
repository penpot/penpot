#include "catalog/property_definition_collection.h"

#include <map>
#include <sstream>

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/string_utils.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace catalog {

std::vector<binder::PropertyDefinition> PropertyDefinitionCollection::getDefinitions() const {
    std::vector<binder::PropertyDefinition> propertyDefinitions;
    for (auto i = 0u; i < nextPropertyID; i++) {
        if (definitions.contains(i)) {
            propertyDefinitions.push_back(definitions.at(i).copy());
        }
    }
    return propertyDefinitions;
}

const PropertyDefinition& PropertyDefinitionCollection::getDefinition(
    const std::string& name) const {
    return getDefinition(getPropertyID(name));
}

const PropertyDefinition& PropertyDefinitionCollection::getDefinition(
    property_id_t propertyID) const {
    KU_ASSERT(definitions.contains(propertyID));
    return definitions.at(propertyID);
}

column_id_t PropertyDefinitionCollection::getColumnID(const std::string& name) const {
    return getColumnID(getPropertyID(name));
}

column_id_t PropertyDefinitionCollection::getColumnID(property_id_t propertyID) const {
    KU_ASSERT(columnIDs.contains(propertyID));
    return columnIDs.at(propertyID);
}

void PropertyDefinitionCollection::vacuumColumnIDs(column_id_t nextColumnID) {
    this->nextColumnID = nextColumnID;
    columnIDs.clear();
    for (auto& [propertyID, definition] : definitions) {
        columnIDs.emplace(propertyID, this->nextColumnID++);
    }
}

void PropertyDefinitionCollection::add(const PropertyDefinition& definition) {
    auto propertyID = nextPropertyID++;
    columnIDs.emplace(propertyID, nextColumnID++);
    definitions.emplace(propertyID, definition.copy());
    nameToPropertyIDMap.emplace(definition.getName(), propertyID);
}

void PropertyDefinitionCollection::drop(const std::string& name) {
    KU_ASSERT(contains(name));
    auto propertyID = nameToPropertyIDMap.at(name);
    definitions.erase(propertyID);
    columnIDs.erase(propertyID);
    nameToPropertyIDMap.erase(name);
}

void PropertyDefinitionCollection::rename(const std::string& name, const std::string& newName) {
    KU_ASSERT(contains(name));
    auto idx = nameToPropertyIDMap.at(name);
    definitions[idx].rename(newName);
    nameToPropertyIDMap.erase(name);
    nameToPropertyIDMap.insert({newName, idx});
}

column_id_t PropertyDefinitionCollection::getMaxColumnID() const {
    column_id_t maxID = 0;
    for (auto [_, id] : columnIDs) {
        if (id > maxID) {
            maxID = id;
        }
    }
    return maxID;
}

property_id_t PropertyDefinitionCollection::getPropertyID(const std::string& name) const {
    KU_ASSERT(contains(name));
    return nameToPropertyIDMap.at(name);
}

std::string PropertyDefinitionCollection::toCypher() const {
    std::stringstream ss;
    for (auto& [_, def] : definitions) {
        auto& dataType = def.getType();
        // Avoid exporting internal ID
        if (dataType.getPhysicalType() == PhysicalTypeID::INTERNAL_ID) {
            continue;
        }
        auto typeStr = dataType.toString();
        StringUtils::replaceAll(typeStr, ":", " ");
        if (typeStr.find("MAP") != std::string::npos) {
            StringUtils::replaceAll(typeStr, "  ", ",");
        }
        ss << "`" << def.getName() << "`" << " " << typeStr << ",";
    }
    return ss.str();
}

void PropertyDefinitionCollection::serialize(Serializer& serializer) const {
    serializer.writeDebuggingInfo("nextColumnID");
    serializer.serializeValue(nextColumnID);
    serializer.writeDebuggingInfo("nextPropertyID");
    serializer.serializeValue(nextPropertyID);
    serializer.writeDebuggingInfo("definitions");
    serializer.serializeMap(definitions);
    serializer.writeDebuggingInfo("columnIDs");
    serializer.serializeUnorderedMap(columnIDs);
}

PropertyDefinitionCollection PropertyDefinitionCollection::deserialize(Deserializer& deserializer) {
    std::string debuggingInfo;
    column_id_t nextColumnID = 0;
    deserializer.validateDebuggingInfo(debuggingInfo, "nextColumnID");
    deserializer.deserializeValue(nextColumnID);
    property_id_t nextPropertyID = 0;
    deserializer.validateDebuggingInfo(debuggingInfo, "nextPropertyID");
    deserializer.deserializeValue(nextPropertyID);
    std::map<property_id_t, PropertyDefinition> definitions;
    deserializer.validateDebuggingInfo(debuggingInfo, "definitions");
    deserializer.deserializeMap(definitions);
    std::unordered_map<property_id_t, column_id_t> columnIDs;
    deserializer.validateDebuggingInfo(debuggingInfo, "columnIDs");
    deserializer.deserializeUnorderedMap(columnIDs);
    auto collection = PropertyDefinitionCollection();
    for (auto& [propertyID, definition] : definitions) {
        collection.nameToPropertyIDMap.insert({definition.getName(), propertyID});
    }
    collection.nextColumnID = nextColumnID;
    collection.nextPropertyID = nextPropertyID;
    collection.definitions = std::move(definitions);
    collection.columnIDs = std::move(columnIDs);
    return collection;
}

} // namespace catalog
} // namespace lbug
