#pragma once

#include <string>

#include "common/copy_constructors.h"
#include "common/enums/conflict_action.h"

namespace lbug {
namespace parser {

enum class SequenceInfoType {
    START,
    INCREMENT,
    MINVALUE,
    MAXVALUE,
    CYCLE,
    INVALID,
};

struct CreateSequenceInfo {
    std::string sequenceName;
    std::string startWith = "";
    std::string increment = "1";
    std::string minValue = "";
    std::string maxValue = "";
    bool cycle = false;
    common::ConflictAction onConflict;

    explicit CreateSequenceInfo(std::string sequenceName, common::ConflictAction onConflict)
        : sequenceName{std::move(sequenceName)}, onConflict{onConflict} {}
    EXPLICIT_COPY_DEFAULT_MOVE(CreateSequenceInfo);

private:
    CreateSequenceInfo(const CreateSequenceInfo& other)
        : sequenceName{other.sequenceName}, startWith{other.startWith}, increment{other.increment},
          minValue{other.minValue}, maxValue{other.maxValue}, cycle{other.cycle},
          onConflict{other.onConflict} {}
};

} // namespace parser
} // namespace lbug
