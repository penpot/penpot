#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct UnionTag {
    static void operation(common::union_entry_t& unionValue, common::ku_string_t& tag,
        common::ValueVector& unionVector, common::ValueVector& tagVector) {
        auto tagIdxVector = common::UnionVector::getTagVector(&unionVector);
        auto tagIdx = tagIdxVector->getValue<common::union_field_idx_t>(unionValue.entry.pos);
        auto tagName = common::UnionType::getFieldName(unionVector.dataType, tagIdx);
        if (tagName.length() > common::ku_string_t::SHORT_STR_LENGTH) {
            tag.overflowPtr =
                reinterpret_cast<uint64_t>(common::StringVector::getInMemOverflowBuffer(&tagVector)
                                               ->allocateSpace(tagName.length()));
            memcpy(reinterpret_cast<char*>(tag.overflowPtr), tagName.c_str(), tagName.length());
            memcpy(tag.prefix, tagName.c_str(), common::ku_string_t::PREFIX_LENGTH);
        } else {
            memcpy(tag.prefix, tagName.c_str(), tagName.length());
        }
        tag.len = tagName.length();
    }
};

} // namespace function
} // namespace lbug
