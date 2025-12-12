#include "function/function_collection.h"

#include "function/aggregate/count.h"
#include "function/aggregate/count_star.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/array/vector_array_functions.h"
#include "function/blob/vector_blob_functions.h"
#include "function/cast/vector_cast_functions.h"
#include "function/comparison/vector_comparison_functions.h"
#include "function/date/vector_date_functions.h"
#include "function/export/export_function.h"
#include "function/hash/vector_hash_functions.h"
#include "function/internal_id/vector_internal_id_functions.h"
#include "function/interval/vector_interval_functions.h"
#include "function/list/vector_list_functions.h"
#include "function/map/vector_map_functions.h"
#include "function/path/vector_path_functions.h"
#include "function/schema/vector_node_rel_functions.h"
#include "function/sequence/sequence_functions.h"
#include "function/string/vector_string_functions.h"
#include "function/struct/vector_struct_functions.h"
#include "function/table/simple_table_function.h"
#include "function/table/standalone_call_function.h"
#include "function/timestamp/vector_timestamp_functions.h"
#include "function/union/vector_union_functions.h"
#include "function/utility/vector_utility_functions.h"
#include "function/uuid/vector_uuid_functions.h"
#include "processor/operator/persistent/reader/csv/parallel_csv_reader.h"
#include "processor/operator/persistent/reader/csv/serial_csv_reader.h"
#include "processor/operator/persistent/reader/npy/npy_reader.h"
#include "processor/operator/persistent/reader/parquet/parquet_reader.h"

using namespace lbug::processor;

namespace lbug {
namespace function {

#define SCALAR_FUNCTION_BASE(_PARAM, _NAME)                                                        \
    { _PARAM::getFunctionSet, _NAME, CatalogEntryType::SCALAR_FUNCTION_ENTRY }
#define SCALAR_FUNCTION(_PARAM) SCALAR_FUNCTION_BASE(_PARAM, _PARAM::name)
#define SCALAR_FUNCTION_ALIAS(_PARAM) SCALAR_FUNCTION_BASE(_PARAM::alias, _PARAM::name)
#define REWRITE_FUNCTION_BASE(_PARAM, _NAME)                                                       \
    { _PARAM::getFunctionSet, _NAME, CatalogEntryType::REWRITE_FUNCTION_ENTRY }
#define REWRITE_FUNCTION(_PARAM) REWRITE_FUNCTION_BASE(_PARAM, _PARAM::name)
#define REWRITE_FUNCTION_ALIAS(_PARAM) REWRITE_FUNCTION_BASE(_PARAM::alias, _PARAM::name)
#define AGGREGATE_FUNCTION(_PARAM)                                                                 \
    { _PARAM::getFunctionSet, _PARAM::name, CatalogEntryType::AGGREGATE_FUNCTION_ENTRY }
#define EXPORT_FUNCTION(_PARAM)                                                                    \
    { _PARAM::getFunctionSet, _PARAM::name, CatalogEntryType::COPY_FUNCTION_ENTRY }
#define TABLE_FUNCTION(_PARAM)                                                                     \
    { _PARAM::getFunctionSet, _PARAM::name, CatalogEntryType::TABLE_FUNCTION_ENTRY }
#define STANDALONE_TABLE_FUNCTION(_PARAM)                                                          \
    { _PARAM::getFunctionSet, _PARAM::name, CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY }
#define FINAL_FUNCTION                                                                             \
    { nullptr, nullptr, CatalogEntryType::SCALAR_FUNCTION_ENTRY }

FunctionCollection* FunctionCollection::getFunctions() {
    static FunctionCollection functions[] = {

        // Arithmetic Functions
        SCALAR_FUNCTION(AddFunction), SCALAR_FUNCTION(SubtractFunction),
        SCALAR_FUNCTION(MultiplyFunction), SCALAR_FUNCTION(DivideFunction),
        SCALAR_FUNCTION(ModuloFunction), SCALAR_FUNCTION(PowerFunction),
        SCALAR_FUNCTION(AbsFunction), SCALAR_FUNCTION(AcosFunction), SCALAR_FUNCTION(AsinFunction),
        SCALAR_FUNCTION(AtanFunction), SCALAR_FUNCTION(Atan2Function),
        SCALAR_FUNCTION(BitwiseXorFunction), SCALAR_FUNCTION(BitwiseAndFunction),
        SCALAR_FUNCTION(BitwiseOrFunction), SCALAR_FUNCTION(BitShiftLeftFunction),
        SCALAR_FUNCTION(BitShiftRightFunction), SCALAR_FUNCTION(CbrtFunction),
        SCALAR_FUNCTION(CeilFunction), SCALAR_FUNCTION_ALIAS(CeilingFunction),
        SCALAR_FUNCTION(CosFunction), SCALAR_FUNCTION(CotFunction),
        SCALAR_FUNCTION(DegreesFunction), SCALAR_FUNCTION(EvenFunction),
        SCALAR_FUNCTION(FactorialFunction), SCALAR_FUNCTION(FloorFunction),
        SCALAR_FUNCTION(GammaFunction), SCALAR_FUNCTION(LgammaFunction),
        SCALAR_FUNCTION(LnFunction), SCALAR_FUNCTION(LogFunction),
        SCALAR_FUNCTION_ALIAS(Log10Function), SCALAR_FUNCTION(Log2Function),
        SCALAR_FUNCTION(NegateFunction), SCALAR_FUNCTION(PiFunction),
        SCALAR_FUNCTION_ALIAS(PowFunction), SCALAR_FUNCTION(RadiansFunction),
        SCALAR_FUNCTION(RoundFunction), SCALAR_FUNCTION(SinFunction), SCALAR_FUNCTION(SignFunction),
        SCALAR_FUNCTION(SqrtFunction), SCALAR_FUNCTION(TanFunction), SCALAR_FUNCTION(RandFunction),
        SCALAR_FUNCTION(SetSeedFunction),

        // String Functions
        SCALAR_FUNCTION(ArrayExtractFunction), SCALAR_FUNCTION(ConcatFunction),
        SCALAR_FUNCTION(ContainsFunction), SCALAR_FUNCTION(LowerFunction),
        SCALAR_FUNCTION_ALIAS(ToLowerFunction), SCALAR_FUNCTION_ALIAS(LcaseFunction),
        SCALAR_FUNCTION(LeftFunction), SCALAR_FUNCTION(LpadFunction),
        SCALAR_FUNCTION(LtrimFunction), SCALAR_FUNCTION(StartsWithFunction),
        SCALAR_FUNCTION_ALIAS(PrefixFunction), SCALAR_FUNCTION(RepeatFunction),
        SCALAR_FUNCTION(ReverseFunction), SCALAR_FUNCTION(RightFunction),
        SCALAR_FUNCTION(RpadFunction), SCALAR_FUNCTION(RtrimFunction),
        SCALAR_FUNCTION(SubStrFunction), SCALAR_FUNCTION_ALIAS(SubstringFunction),
        SCALAR_FUNCTION(EndsWithFunction), SCALAR_FUNCTION_ALIAS(SuffixFunction),
        SCALAR_FUNCTION(TrimFunction), SCALAR_FUNCTION(UpperFunction),
        SCALAR_FUNCTION_ALIAS(UCaseFunction), SCALAR_FUNCTION_ALIAS(ToUpperFunction),
        SCALAR_FUNCTION(RegexpFullMatchFunction), SCALAR_FUNCTION(RegexpMatchesFunction),
        SCALAR_FUNCTION(RegexpReplaceFunction), SCALAR_FUNCTION(RegexpExtractFunction),
        SCALAR_FUNCTION(RegexpExtractAllFunction), SCALAR_FUNCTION(LevenshteinFunction),
        SCALAR_FUNCTION(RegexpSplitToArrayFunction), SCALAR_FUNCTION(InitCapFunction),
        SCALAR_FUNCTION(StringSplitFunction), SCALAR_FUNCTION_ALIAS(StrSplitFunction),
        SCALAR_FUNCTION_ALIAS(StringToArrayFunction), SCALAR_FUNCTION(SplitPartFunction),
        SCALAR_FUNCTION(InternalIDCreationFunction), SCALAR_FUNCTION(ConcatWSFunction),

        // Array Functions
        SCALAR_FUNCTION(ArrayValueFunction), SCALAR_FUNCTION(ArrayCrossProductFunction),
        SCALAR_FUNCTION(ArrayCosineSimilarityFunction), SCALAR_FUNCTION(ArrayDistanceFunction),
        SCALAR_FUNCTION(ArraySquaredDistanceFunction), SCALAR_FUNCTION(ArrayInnerProductFunction),
        SCALAR_FUNCTION(ArrayDotProductFunction),

        // List functions
        SCALAR_FUNCTION(ListCreationFunction), SCALAR_FUNCTION(ListRangeFunction),
        SCALAR_FUNCTION(ListExtractFunction), SCALAR_FUNCTION_ALIAS(ListElementFunction),
        SCALAR_FUNCTION(ListConcatFunction), SCALAR_FUNCTION_ALIAS(ListCatFunction),
        SCALAR_FUNCTION(ArrayConcatFunction), SCALAR_FUNCTION_ALIAS(ArrayCatFunction),
        SCALAR_FUNCTION(ListAppendFunction), SCALAR_FUNCTION(ArrayAppendFunction),
        SCALAR_FUNCTION_ALIAS(ArrayPushFrontFunction), SCALAR_FUNCTION(ListPrependFunction),
        SCALAR_FUNCTION(ArrayPrependFunction), SCALAR_FUNCTION_ALIAS(ArrayPushBackFunction),
        SCALAR_FUNCTION(ListPositionFunction), SCALAR_FUNCTION_ALIAS(ListIndexOfFunction),
        SCALAR_FUNCTION(ArrayPositionFunction), SCALAR_FUNCTION_ALIAS(ArrayIndexOfFunction),
        SCALAR_FUNCTION(ListContainsFunction), SCALAR_FUNCTION_ALIAS(ListHasFunction),
        SCALAR_FUNCTION(ArrayContainsFunction), SCALAR_FUNCTION_ALIAS(ArrayHasFunction),
        SCALAR_FUNCTION(ListSliceFunction), SCALAR_FUNCTION(ArraySliceFunction),
        SCALAR_FUNCTION(ListSortFunction), SCALAR_FUNCTION(ListReverseSortFunction),
        SCALAR_FUNCTION(ListSumFunction), SCALAR_FUNCTION(ListProductFunction),
        SCALAR_FUNCTION(ListDistinctFunction), SCALAR_FUNCTION(ListUniqueFunction),
        SCALAR_FUNCTION(ListAnyValueFunction), SCALAR_FUNCTION(ListReverseFunction),
        SCALAR_FUNCTION(SizeFunction), SCALAR_FUNCTION(ListToStringFunction),
        SCALAR_FUNCTION(ListTransformFunction), SCALAR_FUNCTION(ListFilterFunction),
        SCALAR_FUNCTION(ListReduceFunction), SCALAR_FUNCTION(ListAnyFunction),
        SCALAR_FUNCTION(ListAllFunction), SCALAR_FUNCTION(ListNoneFunction),
        SCALAR_FUNCTION(ListSingleFunction), SCALAR_FUNCTION(ListHasAllFunction),

        // Cast functions
        SCALAR_FUNCTION(CastToDateFunction), SCALAR_FUNCTION_ALIAS(DateFunction),
        SCALAR_FUNCTION(CastToTimestampFunction), SCALAR_FUNCTION(CastToIntervalFunction),
        SCALAR_FUNCTION_ALIAS(IntervalFunctionAlias), SCALAR_FUNCTION_ALIAS(DurationFunction),
        SCALAR_FUNCTION(CastToStringFunction), SCALAR_FUNCTION_ALIAS(StringFunction),
        SCALAR_FUNCTION(CastToBlobFunction), SCALAR_FUNCTION_ALIAS(BlobFunction),
        SCALAR_FUNCTION(CastToUUIDFunction), SCALAR_FUNCTION_ALIAS(UUIDFunction),
        SCALAR_FUNCTION(CastToDoubleFunction), SCALAR_FUNCTION(CastToFloatFunction),
        SCALAR_FUNCTION(CastToSerialFunction), SCALAR_FUNCTION(CastToInt64Function),
        SCALAR_FUNCTION(CastToInt32Function), SCALAR_FUNCTION(CastToInt16Function),
        SCALAR_FUNCTION(CastToInt8Function), SCALAR_FUNCTION(CastToUInt64Function),
        SCALAR_FUNCTION(CastToUInt32Function), SCALAR_FUNCTION(CastToUInt16Function),
        SCALAR_FUNCTION(CastToUInt8Function), SCALAR_FUNCTION(CastToInt128Function),
        SCALAR_FUNCTION(CastToUInt128Function), SCALAR_FUNCTION(CastToBoolFunction),
        SCALAR_FUNCTION(CastAnyFunction),

        // Comparison functions
        SCALAR_FUNCTION(EqualsFunction), SCALAR_FUNCTION(NotEqualsFunction),
        SCALAR_FUNCTION(GreaterThanFunction), SCALAR_FUNCTION(GreaterThanEqualsFunction),
        SCALAR_FUNCTION(LessThanFunction), SCALAR_FUNCTION(LessThanEqualsFunction),

        // Date functions
        SCALAR_FUNCTION(DatePartFunction), SCALAR_FUNCTION_ALIAS(DatePartFunctionAlias),
        SCALAR_FUNCTION(DateTruncFunction), SCALAR_FUNCTION_ALIAS(DateTruncFunctionAlias),
        SCALAR_FUNCTION(DayNameFunction), SCALAR_FUNCTION(GreatestFunction),
        SCALAR_FUNCTION(LastDayFunction), SCALAR_FUNCTION(LeastFunction),
        SCALAR_FUNCTION(MakeDateFunction), SCALAR_FUNCTION(MonthNameFunction),
        SCALAR_FUNCTION(CurrentDateFunction),

        // Timestamp functions
        SCALAR_FUNCTION(CenturyFunction), SCALAR_FUNCTION(EpochMsFunction),
        SCALAR_FUNCTION(ToTimestampFunction), SCALAR_FUNCTION(CurrentTimestampFunction),
        SCALAR_FUNCTION(ToEpochMsFunction),

        // Interval functions
        SCALAR_FUNCTION(ToYearsFunction), SCALAR_FUNCTION(ToMonthsFunction),
        SCALAR_FUNCTION(ToDaysFunction), SCALAR_FUNCTION(ToHoursFunction),
        SCALAR_FUNCTION(ToMinutesFunction), SCALAR_FUNCTION(ToSecondsFunction),
        SCALAR_FUNCTION(ToMillisecondsFunction), SCALAR_FUNCTION(ToMicrosecondsFunction),

        // Blob functions
        SCALAR_FUNCTION(OctetLengthFunctions), SCALAR_FUNCTION(EncodeFunctions),
        SCALAR_FUNCTION(DecodeFunctions),

        // UUID functions
        SCALAR_FUNCTION(GenRandomUUIDFunction),

        // Struct functions
        SCALAR_FUNCTION(StructPackFunctions), SCALAR_FUNCTION(StructExtractFunctions),
        REWRITE_FUNCTION(KeysFunctions),

        // Map functions
        SCALAR_FUNCTION(MapCreationFunctions), SCALAR_FUNCTION(MapExtractFunctions),
        SCALAR_FUNCTION_ALIAS(ElementAtFunctions), SCALAR_FUNCTION_ALIAS(CardinalityFunction),
        SCALAR_FUNCTION(MapKeysFunctions), SCALAR_FUNCTION(MapValuesFunctions),

        // Union functions
        SCALAR_FUNCTION(UnionValueFunction), SCALAR_FUNCTION(UnionTagFunction),
        SCALAR_FUNCTION(UnionExtractFunction),

        // Node/rel functions
        SCALAR_FUNCTION(OffsetFunction), REWRITE_FUNCTION(IDFunction),
        REWRITE_FUNCTION(StartNodeFunction), REWRITE_FUNCTION(EndNodeFunction),
        REWRITE_FUNCTION(LabelFunction), REWRITE_FUNCTION_ALIAS(LabelsFunction),
        REWRITE_FUNCTION(CostFunction),

        // Path functions
        SCALAR_FUNCTION(NodesFunction), SCALAR_FUNCTION(RelsFunction),
        SCALAR_FUNCTION_ALIAS(RelationshipsFunction), SCALAR_FUNCTION(PropertiesFunction),
        SCALAR_FUNCTION(IsTrailFunction), SCALAR_FUNCTION(IsACyclicFunction),
        REWRITE_FUNCTION(LengthFunction),

        // Hash functions
        SCALAR_FUNCTION(MD5Function), SCALAR_FUNCTION(SHA256Function),
        SCALAR_FUNCTION(HashFunction),

        // Scalar utility functions
        SCALAR_FUNCTION(CoalesceFunction), SCALAR_FUNCTION(IfNullFunction),
        SCALAR_FUNCTION(ConstantOrNullFunction), SCALAR_FUNCTION(CountIfFunction),
        SCALAR_FUNCTION(ErrorFunction), REWRITE_FUNCTION(NullIfFunction),
        SCALAR_FUNCTION(TypeOfFunction),

        // Sequence functions
        SCALAR_FUNCTION(CurrValFunction), SCALAR_FUNCTION(NextValFunction),

        // Aggregate functions
        AGGREGATE_FUNCTION(CountStarFunction), AGGREGATE_FUNCTION(CountFunction),
        AGGREGATE_FUNCTION(AggregateSumFunction), AGGREGATE_FUNCTION(AggregateAvgFunction),
        AGGREGATE_FUNCTION(AggregateMinFunction), AGGREGATE_FUNCTION(AggregateMaxFunction),
        AGGREGATE_FUNCTION(CollectFunction),

        // Table functions
        TABLE_FUNCTION(CurrentSettingFunction), TABLE_FUNCTION(CatalogVersionFunction),
        TABLE_FUNCTION(DBVersionFunction), TABLE_FUNCTION(ShowTablesFunction),
        TABLE_FUNCTION(FreeSpaceInfoFunction), TABLE_FUNCTION(ShowWarningsFunction),
        TABLE_FUNCTION(TableInfoFunction), TABLE_FUNCTION(ShowConnectionFunction),
        TABLE_FUNCTION(StatsInfoFunction), TABLE_FUNCTION(StorageInfoFunction),
        TABLE_FUNCTION(ShowAttachedDatabasesFunction), TABLE_FUNCTION(ShowSequencesFunction),
        TABLE_FUNCTION(ShowFunctionsFunction), TABLE_FUNCTION(BMInfoFunction),
        TABLE_FUNCTION(FileInfoFunction), TABLE_FUNCTION(ShowLoadedExtensionsFunction),
        TABLE_FUNCTION(ShowOfficialExtensionsFunction), TABLE_FUNCTION(ShowIndexesFunction),
        TABLE_FUNCTION(ShowProjectedGraphsFunction), TABLE_FUNCTION(ProjectedGraphInfoFunction),
        TABLE_FUNCTION(ShowMacrosFunction),

        // Standalone Table functions
        STANDALONE_TABLE_FUNCTION(LocalCacheArrayColumnFunction),
        STANDALONE_TABLE_FUNCTION(ClearWarningsFunction),
        STANDALONE_TABLE_FUNCTION(ProjectGraphNativeFunction),
        STANDALONE_TABLE_FUNCTION(ProjectGraphCypherFunction),
        STANDALONE_TABLE_FUNCTION(DropProjectedGraphFunction),

        // Scan functions
        TABLE_FUNCTION(ParquetScanFunction), TABLE_FUNCTION(NpyScanFunction),
        TABLE_FUNCTION(SerialCSVScan), TABLE_FUNCTION(ParallelCSVScan),

        // Export functions
        EXPORT_FUNCTION(ExportCSVFunction), EXPORT_FUNCTION(ExportParquetFunction),

        // End of array
        FINAL_FUNCTION};

    return functions;
}

} // namespace function
} // namespace lbug
