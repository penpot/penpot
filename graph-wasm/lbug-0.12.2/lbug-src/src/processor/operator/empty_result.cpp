#include "processor/operator/empty_result.h"

namespace lbug {
namespace processor {

bool EmptyResult::getNextTuplesInternal(ExecutionContext*) {
    return false;
}

} // namespace processor
} // namespace lbug
