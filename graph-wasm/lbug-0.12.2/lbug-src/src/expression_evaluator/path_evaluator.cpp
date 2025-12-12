#include "expression_evaluator/path_evaluator.h"

#include "binder/expression/path_expression.h"
#include "binder/expression/rel_expression.h"
#include "common/string_utils.h"

using namespace lbug::main;
using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace evaluator {

// For each result field vector, find its corresponding input field vector if exist.
static std::vector<ValueVector*> getFieldVectors(const LogicalType& inputType,
    const LogicalType& resultType, ValueVector* inputVector) {
    std::vector<ValueVector*> result;
    for (auto& field : StructType::getFields(resultType)) {
        auto fieldName = StringUtils::getUpper(field.getName());
        if (StructType::hasField(inputType, fieldName)) {
            auto idx = StructType::getFieldIdx(inputType, fieldName);
            result.push_back(StructVector::getFieldVector(inputVector, idx).get());
        } else {
            result.push_back(nullptr);
        }
    }
    return result;
}

void PathExpressionEvaluator::init(const processor::ResultSet& resultSet,
    main::ClientContext* clientContext) {
    ExpressionEvaluator::init(resultSet, clientContext);
    auto resultNodesIdx = StructType::getFieldIdx(resultVector->dataType, InternalKeyword::NODES);
    resultNodesVector = StructVector::getFieldVector(resultVector.get(), resultNodesIdx).get();
    auto resultNodesDataVector = ListVector::getDataVector(resultNodesVector);
    for (auto& fieldVector : StructVector::getFieldVectors(resultNodesDataVector)) {
        resultNodesFieldVectors.push_back(fieldVector.get());
    }
    auto resultRelsIdx = StructType::getFieldIdx(resultVector->dataType, InternalKeyword::RELS);
    resultRelsVector = StructVector::getFieldVector(resultVector.get(), resultRelsIdx).get();
    auto resultRelsDataVector = ListVector::getDataVector(resultRelsVector);
    for (auto& fieldVector : StructVector::getFieldVectors(resultRelsDataVector)) {
        resultRelsFieldVectors.push_back(fieldVector.get());
    }
    auto pathExpression = (PathExpression*)expression.get();
    for (auto i = 0u; i < expression->getNumChildren(); ++i) {
        auto child = expression->getChild(i).get();
        auto vectors = std::make_unique<InputVectors>();
        vectors->input = children[i]->resultVector.get();
        switch (child->dataType.getLogicalTypeID()) {
        case LogicalTypeID::NODE: {
            vectors->nodeFieldVectors =
                getFieldVectors(child->dataType, pathExpression->getNodeType(), vectors->input);
        } break;
        case LogicalTypeID::REL: {
            vectors->relFieldVectors =
                getFieldVectors(child->dataType, pathExpression->getRelType(), vectors->input);
        } break;
        case LogicalTypeID::RECURSIVE_REL: {
            auto rel = (RelExpression*)child;
            auto recursiveNode = rel->getRecursiveInfo()->node;
            auto recursiveRel = rel->getRecursiveInfo()->rel;
            auto nodeFieldIdx = StructType::getFieldIdx(child->dataType, InternalKeyword::NODES);
            vectors->nodesInput = StructVector::getFieldVector(vectors->input, nodeFieldIdx).get();
            vectors->nodesDataInput = ListVector::getDataVector(vectors->nodesInput);
            vectors->nodeFieldVectors = getFieldVectors(recursiveNode->dataType,
                pathExpression->getNodeType(), vectors->nodesDataInput);
            auto relFieldIdx =
                StructType::getFieldIdx(vectors->input->dataType, InternalKeyword::RELS);
            vectors->relsInput = StructVector::getFieldVector(vectors->input, relFieldIdx).get();
            vectors->relsDataInput = ListVector::getDataVector(vectors->relsInput);
            vectors->relFieldVectors = getFieldVectors(recursiveRel->dataType,
                pathExpression->getRelType(), vectors->relsDataInput);
        } break;
        default:
            KU_UNREACHABLE;
        }
        inputVectorsPerChild.push_back(std::move(vectors));
    }
}

void PathExpressionEvaluator::evaluate() {
    resultVector->resetAuxiliaryBuffer();
    for (auto& child : children) {
        child->evaluate();
    }
    auto& selVector = resultVector->state->getSelVector();
    for (auto i = 0u; i < selVector.getSelSize(); ++i) {
        auto pos = selVector[i];
        auto numRels = copyRels(pos);
        copyNodes(pos, numRels == 0);
    }
}

static inline uint32_t getCurrentPos(ValueVector* vector, uint32_t pos) {
    if (vector->state->isFlat()) {
        return vector->state->getSelVector()[0];
    }
    return pos;
}

void PathExpressionEvaluator::copyNodes(sel_t resultPos, bool isEmptyRels) {
    auto listSize = 0u;
    // Calculate list size.
    for (auto i = 0u; i < expression->getNumChildren(); ++i) {
        auto child = expression->getChild(i).get();
        switch (child->dataType.getLogicalTypeID()) {
        case LogicalTypeID::NODE: {
            listSize++;
        } break;
        case LogicalTypeID::RECURSIVE_REL: {
            auto vectors = inputVectorsPerChild[i].get();
            auto inputPos = getCurrentPos(vectors->input, resultPos);
            listSize += vectors->nodesInput->getValue<list_entry_t>(inputPos).size;
        } break;
        default:
            break;
        }
    }
    if (isEmptyRels) {
        listSize = 1;
    }
    // Add list entry.
    auto entry = ListVector::addList(resultNodesVector, listSize);
    resultNodesVector->setValue(resultPos, entry);
    // Copy field vectors
    offset_t resultDataPos = entry.offset;
    auto numChildrenToCopy = isEmptyRels ? 1 : expression->getNumChildren();
    for (auto i = 0u; i < numChildrenToCopy; ++i) {
        auto child = expression->getChild(i).get();
        auto vectors = inputVectorsPerChild[i].get();
        auto inputPos = getCurrentPos(vectors->input, resultPos);
        switch (child->dataType.getLogicalTypeID()) {
        case LogicalTypeID::NODE: {
            copyFieldVectors(inputPos, vectors->nodeFieldVectors, resultDataPos,
                resultNodesFieldVectors);
        } break;
        case LogicalTypeID::RECURSIVE_REL: {
            auto& listEntry = vectors->nodesInput->getValue<list_entry_t>(inputPos);
            for (auto j = 0u; j < listEntry.size; ++j) {
                copyFieldVectors(listEntry.offset + j, vectors->nodeFieldVectors, resultDataPos,
                    resultNodesFieldVectors);
            }
        } break;
        default:
            break;
        }
    }
}

uint64_t PathExpressionEvaluator::copyRels(sel_t resultPos) {
    auto listSize = 0u;
    // Calculate list size.
    for (auto i = 0u; i < expression->getNumChildren(); ++i) {
        auto child = expression->getChild(i).get();
        switch (child->dataType.getLogicalTypeID()) {
        case LogicalTypeID::REL: {
            listSize++;
        } break;
        case LogicalTypeID::RECURSIVE_REL: {
            auto vectors = inputVectorsPerChild[i].get();
            auto inputPos = getCurrentPos(vectors->input, resultPos);
            listSize += vectors->relsInput->getValue<list_entry_t>(inputPos).size;
        } break;
        default:
            break;
        }
    }
    // Add list entry.
    auto entry = ListVector::addList(resultRelsVector, listSize);
    resultRelsVector->setValue(resultPos, entry);
    // Copy field vectors
    offset_t resultDataPos = entry.offset;
    for (auto i = 0u; i < expression->getNumChildren(); ++i) {
        auto child = expression->getChild(i).get();
        auto vectors = inputVectorsPerChild[i].get();
        auto inputPos = getCurrentPos(vectors->input, resultPos);
        switch (child->dataType.getLogicalTypeID()) {
        case LogicalTypeID::REL: {
            copyFieldVectors(inputPos, vectors->relFieldVectors, resultDataPos,
                resultRelsFieldVectors);
        } break;
        case LogicalTypeID::RECURSIVE_REL: {
            auto& listEntry = vectors->relsInput->getValue<list_entry_t>(inputPos);
            for (auto j = 0u; j < listEntry.size; ++j) {
                copyFieldVectors(listEntry.offset + j, vectors->relFieldVectors, resultDataPos,
                    resultRelsFieldVectors);
            }
        } break;
        default:
            break;
        }
    }
    return listSize;
}

void PathExpressionEvaluator::copyFieldVectors(offset_t inputVectorPos,
    const std::vector<ValueVector*>& inputFieldVectors, offset_t& resultVectorPos,
    const std::vector<ValueVector*>& resultFieldVectors) {
    KU_ASSERT(resultFieldVectors.size() == inputFieldVectors.size());
    for (auto i = 0u; i < inputFieldVectors.size(); ++i) {
        auto inputFieldVector = inputFieldVectors[i];
        auto resultFieldVector = resultFieldVectors[i];
        if (inputFieldVector == nullptr || inputFieldVector->isNull(inputVectorPos)) {
            resultFieldVector->setNull(resultVectorPos, true);
            continue;
        }
        resultFieldVector->setNull(resultVectorPos, false);
        KU_ASSERT(inputFieldVector->dataType == resultFieldVector->dataType);
        resultFieldVector->copyFromVectorData(resultVectorPos, inputFieldVector, inputVectorPos);
    }
    resultVectorPos++;
}

void PathExpressionEvaluator::resolveResultVector(const processor::ResultSet& /*resultSet*/,
    storage::MemoryManager* memoryManager) {
    resultVector = std::make_shared<ValueVector>(expression->getDataType().copy(), memoryManager);
    std::vector<ExpressionEvaluator*> inputEvaluators;
    inputEvaluators.reserve(children.size());
    for (auto& child : children) {
        inputEvaluators.push_back(child.get());
    }
    resolveResultStateFromChildren(inputEvaluators);
}

} // namespace evaluator
} // namespace lbug
