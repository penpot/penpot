#include "function/aggregate_function.h"
#include "storage/storage_utils.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::processor;

namespace lbug {
namespace function {

/**
 * For collect each grouped key corresponds to a list of values
 * We store this value as a linked list where each element is allocated from the shared overflow
 * buffer
 * The format for each element in the list is the following in order:
 * - The value of the current element
 * - The pointer to the next element in the list
 */
struct CollectListElement {
    CollectListElement() : elementPtr(nullptr) {}
    explicit CollectListElement(uint8_t* elementPtr) : elementPtr(elementPtr) {}

    CollectListElement getNextElement() const { return CollectListElement{*getNextElementPtr()}; }
    uint8_t** getNextElementPtr() const { return reinterpret_cast<uint8_t**>(elementPtr); }
    void setNextElement(CollectListElement next) const {
        KU_ASSERT(*getNextElementPtr() == nullptr);
        *getNextElementPtr() = next.elementPtr;
    }
    void setNextElement(std::nullptr_t next) const { *getNextElementPtr() = next; }
    uint8_t* getDataPtr() const { return elementPtr + sizeof(uint8_t*); }

    static uint64_t size(LogicalType& elementType) {
        return sizeof(uint8_t*) + StorageUtils::getDataTypeSize(elementType);
    }

    bool valid() const { return elementPtr; }

    uint8_t* elementPtr;
};

struct CollectState : public AggregateStateWithNull {
    CollectState() = default;
    uint32_t getStateSize() const override { return sizeof(*this); }
    void writeToVector(common::ValueVector* outputVector, uint64_t pos) override;

    void appendElement(ValueVector* input, uint32_t pos, InMemOverflowBuffer* overflowBuffer);
    void resetList();
    void appendList(const CollectState& o);

    // We store the head + tail of the linked list
    CollectListElement head;
    CollectListElement tail;
    uint64_t listSize = 0;

    // CollectStates are stored in factorizedTable entries. When the factorizedTable is
    // destructed, the destructor of CollectStates won't be called. Therefore, we need to make sure
    // that no additional actions are required for destructing the head/tail outside of deallocating
    // their memory

    static_assert(std::is_trivially_destructible_v<CollectListElement>);
};

void CollectState::appendList(const CollectState& o) {
    if (head.valid()) {
        KU_ASSERT(tail.valid());
        tail.setNextElement(o.head);
        tail = o.tail;
    } else {
        head = o.head;
        tail = o.tail;
    }
    listSize += o.listSize;
}

void CollectState::appendElement(ValueVector* input, uint32_t pos,
    InMemOverflowBuffer* overflowBuffer) {
    CollectListElement newElement{
        overflowBuffer->allocateSpace(CollectListElement::size(input->dataType))};
    newElement.setNextElement(nullptr);
    input->copyToRowData(pos, newElement.getDataPtr(), overflowBuffer);

    if (tail.valid()) {
        tail.setNextElement(newElement);
    } else {
        KU_ASSERT(!head.valid());
        head = newElement;
    }
    tail = newElement;

    ++listSize;
}

void CollectState::resetList() {
    head = {};
    tail = {};
    listSize = 0;
}

void CollectState::writeToVector(common::ValueVector* outputVector, uint64_t pos) {
    auto listEntry = common::ListVector::addList(outputVector, listSize);
    outputVector->setValue<common::list_entry_t>(pos, listEntry);
    auto outputDataVector = common::ListVector::getDataVector(outputVector);
    CollectListElement curElement = head;
    for (auto i = 0u; i < listEntry.size; i++) {
        KU_ASSERT(curElement.valid());
        outputDataVector->copyFromRowData(listEntry.offset + i, curElement.getDataPtr());
        curElement = curElement.getNextElement();
    }
}

static std::unique_ptr<AggregateState> initialize() {
    return std::make_unique<CollectState>();
}

static void updateSingleValue(CollectState* state, ValueVector* input, uint32_t pos,
    uint64_t multiplicity, InMemOverflowBuffer* overflowBuffer) {
    for (auto i = 0u; i < multiplicity; ++i) {
        state->isNull = false;
        state->appendElement(input, pos, overflowBuffer);
    }
}

static void updateAll(uint8_t* state_, ValueVector* input, uint64_t multiplicity,
    InMemOverflowBuffer* overflowBuffer) {
    KU_ASSERT(!input->state->isFlat());
    auto state = reinterpret_cast<CollectState*>(state_);
    auto& inputSelVector = input->state->getSelVector();
    if (input->hasNoNullsGuarantee()) {
        for (auto i = 0u; i < inputSelVector.getSelSize(); ++i) {
            auto pos = inputSelVector[i];
            updateSingleValue(state, input, pos, multiplicity, overflowBuffer);
        }
    } else {
        for (auto i = 0u; i < inputSelVector.getSelSize(); ++i) {
            auto pos = inputSelVector[i];
            if (!input->isNull(pos)) {
                updateSingleValue(state, input, pos, multiplicity, overflowBuffer);
            }
        }
    }
}

static void updatePos(uint8_t* state_, ValueVector* input, uint64_t multiplicity, uint32_t pos,
    InMemOverflowBuffer* overflowBuffer) {
    auto state = reinterpret_cast<CollectState*>(state_);
    updateSingleValue(state, input, pos, multiplicity, overflowBuffer);
}

static void finalize(uint8_t* /*state_*/) {}

static void combine(uint8_t* state_, uint8_t* otherState_,
    InMemOverflowBuffer* /*overflowBuffer*/) {
    auto otherState = reinterpret_cast<CollectState*>(otherState_);
    if (otherState->isNull) {
        return;
    }
    auto state = reinterpret_cast<CollectState*>(state_);
    state->appendList(*otherState);
    state->isNull = false;
    otherState->resetList();
    otherState->isNull = true;
}

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto aggFuncDefinition = reinterpret_cast<AggregateFunction*>(input.definition);
    aggFuncDefinition->parameterTypeIDs[0] = input.arguments[0]->dataType.getLogicalTypeID();
    auto returnType = LogicalType::LIST(input.arguments[0]->dataType.copy());
    return std::make_unique<FunctionBindData>(std::move(returnType));
}

function_set CollectFunction::getFunctionSet() {
    function_set result;
    for (auto isDistinct : std::vector<bool>{true, false}) {
        result.push_back(std::make_unique<AggregateFunction>(name,
            std::vector<LogicalTypeID>{LogicalTypeID::ANY}, LogicalTypeID::LIST, initialize,
            updateAll, updatePos, combine, finalize, isDistinct, bindFunc,
            nullptr /* paramRewriteFunc */));
    }
    return result;
}

} // namespace function
} // namespace lbug
