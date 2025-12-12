#pragma once

#include <cstdint>

namespace lbug {

namespace testing {
class BaseGraphTest;
class PrivateGraphTest;
class TestHelper;
class TestRunner;
} // namespace testing

namespace benchmark {
class Benchmark;
} // namespace benchmark

namespace binder {
class Expression;
class BoundStatementResult;
class PropertyExpression;
} // namespace binder

namespace catalog {
class Catalog;
} // namespace catalog

namespace common {
enum class StatementType : uint8_t;
class Value;
struct FileInfo;
class VirtualFileSystem;
} // namespace common

namespace storage {
class MemoryManager;
class BufferManager;
class StorageManager;
class WAL;
enum class WALReplayMode : uint8_t;
} // namespace storage

namespace planner {
class LogicalOperator;
class LogicalPlan;
} // namespace planner

namespace processor {
class QueryProcessor;
class FactorizedTable;
class FlatTupleIterator;
class PhysicalOperator;
class PhysicalPlan;
} // namespace processor

namespace transaction {
class Transaction;
class TransactionManager;
class TransactionContext;
} // namespace transaction

} // namespace lbug
