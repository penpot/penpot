
// Generated from Cypher.g4 by ANTLR 4.13.1

#pragma once


#include "antlr4-runtime.h"




class  CypherParser : public antlr4::Parser {
public:
  enum {
    T__0 = 1, T__1 = 2, T__2 = 3, T__3 = 4, T__4 = 5, T__5 = 6, T__6 = 7, 
    T__7 = 8, T__8 = 9, T__9 = 10, T__10 = 11, T__11 = 12, T__12 = 13, T__13 = 14, 
    T__14 = 15, T__15 = 16, T__16 = 17, T__17 = 18, T__18 = 19, T__19 = 20, 
    T__20 = 21, T__21 = 22, T__22 = 23, T__23 = 24, T__24 = 25, T__25 = 26, 
    T__26 = 27, T__27 = 28, T__28 = 29, T__29 = 30, T__30 = 31, T__31 = 32, 
    T__32 = 33, T__33 = 34, T__34 = 35, T__35 = 36, T__36 = 37, T__37 = 38, 
    T__38 = 39, T__39 = 40, T__40 = 41, T__41 = 42, T__42 = 43, T__43 = 44, 
    ACYCLIC = 45, ANY = 46, ADD = 47, ALL = 48, ALTER = 49, AND = 50, AS = 51, 
    ASC = 52, ASCENDING = 53, ATTACH = 54, BEGIN = 55, BY = 56, CALL = 57, 
    CASE = 58, CAST = 59, CHECKPOINT = 60, COLUMN = 61, COMMENT = 62, COMMIT = 63, 
    COMMIT_SKIP_CHECKPOINT = 64, CONTAINS = 65, COPY = 66, COUNT = 67, CREATE = 68, 
    CYCLE = 69, DATABASE = 70, DBTYPE = 71, DEFAULT = 72, DELETE = 73, DESC = 74, 
    DESCENDING = 75, DETACH = 76, DISTINCT = 77, DROP = 78, ELSE = 79, END = 80, 
    ENDS = 81, EXISTS = 82, EXPLAIN = 83, EXPORT = 84, EXTENSION = 85, FALSE = 86, 
    FROM = 87, FORCE = 88, GLOB = 89, GRAPH = 90, GROUP = 91, HEADERS = 92, 
    HINT = 93, IMPORT = 94, IF = 95, IN = 96, INCREMENT = 97, INSTALL = 98, 
    IS = 99, JOIN = 100, KEY = 101, LIMIT = 102, LOAD = 103, LOGICAL = 104, 
    MACRO = 105, MATCH = 106, MAXVALUE = 107, MERGE = 108, MINVALUE = 109, 
    MULTI_JOIN = 110, NO = 111, NODE = 112, NOT = 113, NONE = 114, NULL_ = 115, 
    ON = 116, ONLY = 117, OPTIONAL = 118, OR = 119, ORDER = 120, PRIMARY = 121, 
    PROFILE = 122, PROJECT = 123, READ = 124, REL = 125, RENAME = 126, RETURN = 127, 
    ROLLBACK = 128, ROLLBACK_SKIP_CHECKPOINT = 129, SEQUENCE = 130, SET = 131, 
    SHORTEST = 132, START = 133, STARTS = 134, STRUCT = 135, TABLE = 136, 
    THEN = 137, TO = 138, TRAIL = 139, TRANSACTION = 140, TRUE = 141, TYPE = 142, 
    UNION = 143, UNWIND = 144, UNINSTALL = 145, UPDATE = 146, USE = 147, 
    WHEN = 148, WHERE = 149, WITH = 150, WRITE = 151, WSHORTEST = 152, XOR = 153, 
    SINGLE = 154, YIELD = 155, USER = 156, PASSWORD = 157, ROLE = 158, MAP = 159, 
    DECIMAL = 160, STAR = 161, L_SKIP = 162, INVALID_NOT_EQUAL = 163, COLON = 164, 
    DOTDOT = 165, MINUS = 166, FACTORIAL = 167, StringLiteral = 168, EscapedChar = 169, 
    DecimalInteger = 170, HexLetter = 171, HexDigit = 172, Digit = 173, 
    NonZeroDigit = 174, NonZeroOctDigit = 175, ZeroDigit = 176, ExponentDecimalReal = 177, 
    RegularDecimalReal = 178, UnescapedSymbolicName = 179, IdentifierStart = 180, 
    IdentifierPart = 181, EscapedSymbolicName = 182, SP = 183, WHITESPACE = 184, 
    CypherComment = 185, Unknown = 186
  };

  enum {
    RuleKu_Statements = 0, RuleOC_Cypher = 1, RuleOC_Statement = 2, RuleKU_CopyFrom = 3, 
    RuleKU_ColumnNames = 4, RuleKU_ScanSource = 5, RuleKU_CopyFromByColumn = 6, 
    RuleKU_CopyTO = 7, RuleKU_ExportDatabase = 8, RuleKU_ImportDatabase = 9, 
    RuleKU_AttachDatabase = 10, RuleKU_Option = 11, RuleKU_Options = 12, 
    RuleKU_DetachDatabase = 13, RuleKU_UseDatabase = 14, RuleKU_StandaloneCall = 15, 
    RuleKU_CommentOn = 16, RuleKU_CreateMacro = 17, RuleKU_PositionalArgs = 18, 
    RuleKU_DefaultArg = 19, RuleKU_FilePaths = 20, RuleKU_IfNotExists = 21, 
    RuleKU_CreateNodeTable = 22, RuleKU_CreateRelTable = 23, RuleKU_FromToConnections = 24, 
    RuleKU_FromToConnection = 25, RuleKU_CreateSequence = 26, RuleKU_CreateType = 27, 
    RuleKU_SequenceOptions = 28, RuleKU_WithPasswd = 29, RuleKU_CreateUser = 30, 
    RuleKU_CreateRole = 31, RuleKU_IncrementBy = 32, RuleKU_MinValue = 33, 
    RuleKU_MaxValue = 34, RuleKU_StartWith = 35, RuleKU_Cycle = 36, RuleKU_IfExists = 37, 
    RuleKU_Drop = 38, RuleKU_AlterTable = 39, RuleKU_AlterOptions = 40, 
    RuleKU_AddProperty = 41, RuleKU_Default = 42, RuleKU_DropProperty = 43, 
    RuleKU_RenameTable = 44, RuleKU_RenameProperty = 45, RuleKU_AddFromToConnection = 46, 
    RuleKU_DropFromToConnection = 47, RuleKU_ColumnDefinitions = 48, RuleKU_ColumnDefinition = 49, 
    RuleKU_PropertyDefinitions = 50, RuleKU_PropertyDefinition = 51, RuleKU_CreateNodeConstraint = 52, 
    RuleKU_UnionType = 53, RuleKU_StructType = 54, RuleKU_MapType = 55, 
    RuleKU_DecimalType = 56, RuleKU_DataType = 57, RuleKU_ListIdentifiers = 58, 
    RuleKU_ListIdentifier = 59, RuleOC_AnyCypherOption = 60, RuleOC_Explain = 61, 
    RuleOC_Profile = 62, RuleKU_Transaction = 63, RuleKU_Extension = 64, 
    RuleKU_LoadExtension = 65, RuleKU_InstallExtension = 66, RuleKU_UninstallExtension = 67, 
    RuleKU_UpdateExtension = 68, RuleOC_Query = 69, RuleOC_RegularQuery = 70, 
    RuleOC_Union = 71, RuleOC_SingleQuery = 72, RuleOC_SinglePartQuery = 73, 
    RuleOC_MultiPartQuery = 74, RuleKU_QueryPart = 75, RuleOC_UpdatingClause = 76, 
    RuleOC_ReadingClause = 77, RuleKU_LoadFrom = 78, RuleOC_YieldItem = 79, 
    RuleOC_YieldItems = 80, RuleKU_InQueryCall = 81, RuleOC_Match = 82, 
    RuleKU_Hint = 83, RuleKU_JoinNode = 84, RuleOC_Unwind = 85, RuleOC_Create = 86, 
    RuleOC_Merge = 87, RuleOC_MergeAction = 88, RuleOC_Set = 89, RuleOC_SetItem = 90, 
    RuleOC_Delete = 91, RuleOC_With = 92, RuleOC_Return = 93, RuleOC_ProjectionBody = 94, 
    RuleOC_ProjectionItems = 95, RuleOC_ProjectionItem = 96, RuleOC_Order = 97, 
    RuleOC_Skip = 98, RuleOC_Limit = 99, RuleOC_SortItem = 100, RuleOC_Where = 101, 
    RuleOC_Pattern = 102, RuleOC_PatternPart = 103, RuleOC_AnonymousPatternPart = 104, 
    RuleOC_PatternElement = 105, RuleOC_NodePattern = 106, RuleOC_PatternElementChain = 107, 
    RuleOC_RelationshipPattern = 108, RuleOC_RelationshipDetail = 109, RuleKU_Properties = 110, 
    RuleOC_RelationshipTypes = 111, RuleOC_NodeLabels = 112, RuleKU_RecursiveDetail = 113, 
    RuleKU_RecursiveType = 114, RuleOC_RangeLiteral = 115, RuleKU_RecursiveComprehension = 116, 
    RuleKU_RecursiveProjectionItems = 117, RuleOC_LowerBound = 118, RuleOC_UpperBound = 119, 
    RuleOC_LabelName = 120, RuleOC_RelTypeName = 121, RuleOC_Expression = 122, 
    RuleOC_OrExpression = 123, RuleOC_XorExpression = 124, RuleOC_AndExpression = 125, 
    RuleOC_NotExpression = 126, RuleOC_ComparisonExpression = 127, RuleKU_ComparisonOperator = 128, 
    RuleKU_BitwiseOrOperatorExpression = 129, RuleKU_BitwiseAndOperatorExpression = 130, 
    RuleKU_BitShiftOperatorExpression = 131, RuleKU_BitShiftOperator = 132, 
    RuleOC_AddOrSubtractExpression = 133, RuleKU_AddOrSubtractOperator = 134, 
    RuleOC_MultiplyDivideModuloExpression = 135, RuleKU_MultiplyDivideModuloOperator = 136, 
    RuleOC_PowerOfExpression = 137, RuleOC_StringListNullOperatorExpression = 138, 
    RuleOC_ListOperatorExpression = 139, RuleOC_StringOperatorExpression = 140, 
    RuleOC_RegularExpression = 141, RuleOC_NullOperatorExpression = 142, 
    RuleOC_UnaryAddSubtractOrFactorialExpression = 143, RuleOC_PropertyOrLabelsExpression = 144, 
    RuleOC_Atom = 145, RuleOC_Quantifier = 146, RuleOC_FilterExpression = 147, 
    RuleOC_IdInColl = 148, RuleOC_Literal = 149, RuleOC_BooleanLiteral = 150, 
    RuleOC_ListLiteral = 151, RuleKU_ListEntry = 152, RuleKU_StructLiteral = 153, 
    RuleKU_StructField = 154, RuleOC_ParenthesizedExpression = 155, RuleOC_FunctionInvocation = 156, 
    RuleOC_FunctionName = 157, RuleKU_FunctionParameter = 158, RuleKU_LambdaParameter = 159, 
    RuleKU_LambdaVars = 160, RuleOC_PathPatterns = 161, RuleOC_ExistCountSubquery = 162, 
    RuleOC_PropertyLookup = 163, RuleOC_CaseExpression = 164, RuleOC_CaseAlternative = 165, 
    RuleOC_Variable = 166, RuleOC_NumberLiteral = 167, RuleOC_Parameter = 168, 
    RuleOC_PropertyExpression = 169, RuleOC_PropertyKeyName = 170, RuleOC_IntegerLiteral = 171, 
    RuleOC_DoubleLiteral = 172, RuleOC_SchemaName = 173, RuleOC_SymbolicName = 174, 
    RuleKU_NonReservedKeywords = 175, RuleOC_LeftArrowHead = 176, RuleOC_RightArrowHead = 177, 
    RuleOC_Dash = 178
  };

  explicit CypherParser(antlr4::TokenStream *input);

  CypherParser(antlr4::TokenStream *input, const antlr4::atn::ParserATNSimulatorOptions &options);

  ~CypherParser() override;

  std::string getGrammarFileName() const override;

  const antlr4::atn::ATN& getATN() const override;

  const std::vector<std::string>& getRuleNames() const override;

  const antlr4::dfa::Vocabulary& getVocabulary() const override;

  antlr4::atn::SerializedATNView getSerializedATN() const override;


  class Ku_StatementsContext;
  class OC_CypherContext;
  class OC_StatementContext;
  class KU_CopyFromContext;
  class KU_ColumnNamesContext;
  class KU_ScanSourceContext;
  class KU_CopyFromByColumnContext;
  class KU_CopyTOContext;
  class KU_ExportDatabaseContext;
  class KU_ImportDatabaseContext;
  class KU_AttachDatabaseContext;
  class KU_OptionContext;
  class KU_OptionsContext;
  class KU_DetachDatabaseContext;
  class KU_UseDatabaseContext;
  class KU_StandaloneCallContext;
  class KU_CommentOnContext;
  class KU_CreateMacroContext;
  class KU_PositionalArgsContext;
  class KU_DefaultArgContext;
  class KU_FilePathsContext;
  class KU_IfNotExistsContext;
  class KU_CreateNodeTableContext;
  class KU_CreateRelTableContext;
  class KU_FromToConnectionsContext;
  class KU_FromToConnectionContext;
  class KU_CreateSequenceContext;
  class KU_CreateTypeContext;
  class KU_SequenceOptionsContext;
  class KU_WithPasswdContext;
  class KU_CreateUserContext;
  class KU_CreateRoleContext;
  class KU_IncrementByContext;
  class KU_MinValueContext;
  class KU_MaxValueContext;
  class KU_StartWithContext;
  class KU_CycleContext;
  class KU_IfExistsContext;
  class KU_DropContext;
  class KU_AlterTableContext;
  class KU_AlterOptionsContext;
  class KU_AddPropertyContext;
  class KU_DefaultContext;
  class KU_DropPropertyContext;
  class KU_RenameTableContext;
  class KU_RenamePropertyContext;
  class KU_AddFromToConnectionContext;
  class KU_DropFromToConnectionContext;
  class KU_ColumnDefinitionsContext;
  class KU_ColumnDefinitionContext;
  class KU_PropertyDefinitionsContext;
  class KU_PropertyDefinitionContext;
  class KU_CreateNodeConstraintContext;
  class KU_UnionTypeContext;
  class KU_StructTypeContext;
  class KU_MapTypeContext;
  class KU_DecimalTypeContext;
  class KU_DataTypeContext;
  class KU_ListIdentifiersContext;
  class KU_ListIdentifierContext;
  class OC_AnyCypherOptionContext;
  class OC_ExplainContext;
  class OC_ProfileContext;
  class KU_TransactionContext;
  class KU_ExtensionContext;
  class KU_LoadExtensionContext;
  class KU_InstallExtensionContext;
  class KU_UninstallExtensionContext;
  class KU_UpdateExtensionContext;
  class OC_QueryContext;
  class OC_RegularQueryContext;
  class OC_UnionContext;
  class OC_SingleQueryContext;
  class OC_SinglePartQueryContext;
  class OC_MultiPartQueryContext;
  class KU_QueryPartContext;
  class OC_UpdatingClauseContext;
  class OC_ReadingClauseContext;
  class KU_LoadFromContext;
  class OC_YieldItemContext;
  class OC_YieldItemsContext;
  class KU_InQueryCallContext;
  class OC_MatchContext;
  class KU_HintContext;
  class KU_JoinNodeContext;
  class OC_UnwindContext;
  class OC_CreateContext;
  class OC_MergeContext;
  class OC_MergeActionContext;
  class OC_SetContext;
  class OC_SetItemContext;
  class OC_DeleteContext;
  class OC_WithContext;
  class OC_ReturnContext;
  class OC_ProjectionBodyContext;
  class OC_ProjectionItemsContext;
  class OC_ProjectionItemContext;
  class OC_OrderContext;
  class OC_SkipContext;
  class OC_LimitContext;
  class OC_SortItemContext;
  class OC_WhereContext;
  class OC_PatternContext;
  class OC_PatternPartContext;
  class OC_AnonymousPatternPartContext;
  class OC_PatternElementContext;
  class OC_NodePatternContext;
  class OC_PatternElementChainContext;
  class OC_RelationshipPatternContext;
  class OC_RelationshipDetailContext;
  class KU_PropertiesContext;
  class OC_RelationshipTypesContext;
  class OC_NodeLabelsContext;
  class KU_RecursiveDetailContext;
  class KU_RecursiveTypeContext;
  class OC_RangeLiteralContext;
  class KU_RecursiveComprehensionContext;
  class KU_RecursiveProjectionItemsContext;
  class OC_LowerBoundContext;
  class OC_UpperBoundContext;
  class OC_LabelNameContext;
  class OC_RelTypeNameContext;
  class OC_ExpressionContext;
  class OC_OrExpressionContext;
  class OC_XorExpressionContext;
  class OC_AndExpressionContext;
  class OC_NotExpressionContext;
  class OC_ComparisonExpressionContext;
  class KU_ComparisonOperatorContext;
  class KU_BitwiseOrOperatorExpressionContext;
  class KU_BitwiseAndOperatorExpressionContext;
  class KU_BitShiftOperatorExpressionContext;
  class KU_BitShiftOperatorContext;
  class OC_AddOrSubtractExpressionContext;
  class KU_AddOrSubtractOperatorContext;
  class OC_MultiplyDivideModuloExpressionContext;
  class KU_MultiplyDivideModuloOperatorContext;
  class OC_PowerOfExpressionContext;
  class OC_StringListNullOperatorExpressionContext;
  class OC_ListOperatorExpressionContext;
  class OC_StringOperatorExpressionContext;
  class OC_RegularExpressionContext;
  class OC_NullOperatorExpressionContext;
  class OC_UnaryAddSubtractOrFactorialExpressionContext;
  class OC_PropertyOrLabelsExpressionContext;
  class OC_AtomContext;
  class OC_QuantifierContext;
  class OC_FilterExpressionContext;
  class OC_IdInCollContext;
  class OC_LiteralContext;
  class OC_BooleanLiteralContext;
  class OC_ListLiteralContext;
  class KU_ListEntryContext;
  class KU_StructLiteralContext;
  class KU_StructFieldContext;
  class OC_ParenthesizedExpressionContext;
  class OC_FunctionInvocationContext;
  class OC_FunctionNameContext;
  class KU_FunctionParameterContext;
  class KU_LambdaParameterContext;
  class KU_LambdaVarsContext;
  class OC_PathPatternsContext;
  class OC_ExistCountSubqueryContext;
  class OC_PropertyLookupContext;
  class OC_CaseExpressionContext;
  class OC_CaseAlternativeContext;
  class OC_VariableContext;
  class OC_NumberLiteralContext;
  class OC_ParameterContext;
  class OC_PropertyExpressionContext;
  class OC_PropertyKeyNameContext;
  class OC_IntegerLiteralContext;
  class OC_DoubleLiteralContext;
  class OC_SchemaNameContext;
  class OC_SymbolicNameContext;
  class KU_NonReservedKeywordsContext;
  class OC_LeftArrowHeadContext;
  class OC_RightArrowHeadContext;
  class OC_DashContext; 

  class  Ku_StatementsContext : public antlr4::ParserRuleContext {
  public:
    Ku_StatementsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_CypherContext *> oC_Cypher();
    OC_CypherContext* oC_Cypher(size_t i);
    antlr4::tree::TerminalNode *EOF();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  Ku_StatementsContext* ku_Statements();

  class  OC_CypherContext : public antlr4::ParserRuleContext {
  public:
    OC_CypherContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_StatementContext *oC_Statement();
    OC_AnyCypherOptionContext *oC_AnyCypherOption();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_CypherContext* oC_Cypher();

  class  OC_StatementContext : public antlr4::ParserRuleContext {
  public:
    OC_StatementContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_QueryContext *oC_Query();
    KU_CreateUserContext *kU_CreateUser();
    KU_CreateRoleContext *kU_CreateRole();
    KU_CreateNodeTableContext *kU_CreateNodeTable();
    KU_CreateRelTableContext *kU_CreateRelTable();
    KU_CreateSequenceContext *kU_CreateSequence();
    KU_CreateTypeContext *kU_CreateType();
    KU_DropContext *kU_Drop();
    KU_AlterTableContext *kU_AlterTable();
    KU_CopyFromContext *kU_CopyFrom();
    KU_CopyFromByColumnContext *kU_CopyFromByColumn();
    KU_CopyTOContext *kU_CopyTO();
    KU_StandaloneCallContext *kU_StandaloneCall();
    KU_CreateMacroContext *kU_CreateMacro();
    KU_CommentOnContext *kU_CommentOn();
    KU_TransactionContext *kU_Transaction();
    KU_ExtensionContext *kU_Extension();
    KU_ExportDatabaseContext *kU_ExportDatabase();
    KU_ImportDatabaseContext *kU_ImportDatabase();
    KU_AttachDatabaseContext *kU_AttachDatabase();
    KU_DetachDatabaseContext *kU_DetachDatabase();
    KU_UseDatabaseContext *kU_UseDatabase();

   
  };

  OC_StatementContext* oC_Statement();

  class  KU_CopyFromContext : public antlr4::ParserRuleContext {
  public:
    KU_CopyFromContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COPY();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_SchemaNameContext *oC_SchemaName();
    antlr4::tree::TerminalNode *FROM();
    KU_ScanSourceContext *kU_ScanSource();
    KU_ColumnNamesContext *kU_ColumnNames();
    KU_OptionsContext *kU_Options();

   
  };

  KU_CopyFromContext* kU_CopyFrom();

  class  KU_ColumnNamesContext : public antlr4::ParserRuleContext {
  public:
    KU_ColumnNamesContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_SchemaNameContext *> oC_SchemaName();
    OC_SchemaNameContext* oC_SchemaName(size_t i);

   
  };

  KU_ColumnNamesContext* kU_ColumnNames();

  class  KU_ScanSourceContext : public antlr4::ParserRuleContext {
  public:
    KU_ScanSourceContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_FilePathsContext *kU_FilePaths();
    OC_QueryContext *oC_Query();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_ParameterContext *oC_Parameter();
    OC_VariableContext *oC_Variable();
    OC_SchemaNameContext *oC_SchemaName();
    OC_FunctionInvocationContext *oC_FunctionInvocation();

   
  };

  KU_ScanSourceContext* kU_ScanSource();

  class  KU_CopyFromByColumnContext : public antlr4::ParserRuleContext {
  public:
    KU_CopyFromByColumnContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COPY();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_SchemaNameContext *oC_SchemaName();
    antlr4::tree::TerminalNode *FROM();
    std::vector<antlr4::tree::TerminalNode *> StringLiteral();
    antlr4::tree::TerminalNode* StringLiteral(size_t i);
    antlr4::tree::TerminalNode *BY();
    antlr4::tree::TerminalNode *COLUMN();

   
  };

  KU_CopyFromByColumnContext* kU_CopyFromByColumn();

  class  KU_CopyTOContext : public antlr4::ParserRuleContext {
  public:
    KU_CopyTOContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COPY();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_QueryContext *oC_Query();
    antlr4::tree::TerminalNode *TO();
    antlr4::tree::TerminalNode *StringLiteral();
    KU_OptionsContext *kU_Options();

   
  };

  KU_CopyTOContext* kU_CopyTO();

  class  KU_ExportDatabaseContext : public antlr4::ParserRuleContext {
  public:
    KU_ExportDatabaseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *EXPORT();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *DATABASE();
    antlr4::tree::TerminalNode *StringLiteral();
    KU_OptionsContext *kU_Options();

   
  };

  KU_ExportDatabaseContext* kU_ExportDatabase();

  class  KU_ImportDatabaseContext : public antlr4::ParserRuleContext {
  public:
    KU_ImportDatabaseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *IMPORT();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *DATABASE();
    antlr4::tree::TerminalNode *StringLiteral();

   
  };

  KU_ImportDatabaseContext* kU_ImportDatabase();

  class  KU_AttachDatabaseContext : public antlr4::ParserRuleContext {
  public:
    KU_AttachDatabaseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ATTACH();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *StringLiteral();
    antlr4::tree::TerminalNode *DBTYPE();
    OC_SymbolicNameContext *oC_SymbolicName();
    antlr4::tree::TerminalNode *AS();
    OC_SchemaNameContext *oC_SchemaName();
    KU_OptionsContext *kU_Options();

   
  };

  KU_AttachDatabaseContext* kU_AttachDatabase();

  class  KU_OptionContext : public antlr4::ParserRuleContext {
  public:
    KU_OptionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();
    OC_LiteralContext *oC_Literal();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_OptionContext* kU_Option();

  class  KU_OptionsContext : public antlr4::ParserRuleContext {
  public:
    KU_OptionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_OptionContext *> kU_Option();
    KU_OptionContext* kU_Option(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_OptionsContext* kU_Options();

  class  KU_DetachDatabaseContext : public antlr4::ParserRuleContext {
  public:
    KU_DetachDatabaseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DETACH();
    antlr4::tree::TerminalNode *SP();
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  KU_DetachDatabaseContext* kU_DetachDatabase();

  class  KU_UseDatabaseContext : public antlr4::ParserRuleContext {
  public:
    KU_UseDatabaseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *USE();
    antlr4::tree::TerminalNode *SP();
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  KU_UseDatabaseContext* kU_UseDatabase();

  class  KU_StandaloneCallContext : public antlr4::ParserRuleContext {
  public:
    KU_StandaloneCallContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CALL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_SymbolicNameContext *oC_SymbolicName();
    OC_ExpressionContext *oC_Expression();
    OC_FunctionInvocationContext *oC_FunctionInvocation();

   
  };

  KU_StandaloneCallContext* kU_StandaloneCall();

  class  KU_CommentOnContext : public antlr4::ParserRuleContext {
  public:
    KU_CommentOnContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COMMENT();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *ON();
    antlr4::tree::TerminalNode *TABLE();
    OC_SchemaNameContext *oC_SchemaName();
    antlr4::tree::TerminalNode *IS();
    antlr4::tree::TerminalNode *StringLiteral();

   
  };

  KU_CommentOnContext* kU_CommentOn();

  class  KU_CreateMacroContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateMacroContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *MACRO();
    OC_FunctionNameContext *oC_FunctionName();
    antlr4::tree::TerminalNode *AS();
    OC_ExpressionContext *oC_Expression();
    KU_PositionalArgsContext *kU_PositionalArgs();
    std::vector<KU_DefaultArgContext *> kU_DefaultArg();
    KU_DefaultArgContext* kU_DefaultArg(size_t i);

   
  };

  KU_CreateMacroContext* kU_CreateMacro();

  class  KU_PositionalArgsContext : public antlr4::ParserRuleContext {
  public:
    KU_PositionalArgsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_SymbolicNameContext *> oC_SymbolicName();
    OC_SymbolicNameContext* oC_SymbolicName(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_PositionalArgsContext* kU_PositionalArgs();

  class  KU_DefaultArgContext : public antlr4::ParserRuleContext {
  public:
    KU_DefaultArgContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();
    antlr4::tree::TerminalNode *COLON();
    OC_LiteralContext *oC_Literal();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_DefaultArgContext* kU_DefaultArg();

  class  KU_FilePathsContext : public antlr4::ParserRuleContext {
  public:
    KU_FilePathsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> StringLiteral();
    antlr4::tree::TerminalNode* StringLiteral(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *GLOB();

   
  };

  KU_FilePathsContext* kU_FilePaths();

  class  KU_IfNotExistsContext : public antlr4::ParserRuleContext {
  public:
    KU_IfNotExistsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *IF();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *NOT();
    antlr4::tree::TerminalNode *EXISTS();

   
  };

  KU_IfNotExistsContext* kU_IfNotExists();

  class  KU_CreateNodeTableContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateNodeTableContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *NODE();
    antlr4::tree::TerminalNode *TABLE();
    OC_SchemaNameContext *oC_SchemaName();
    KU_PropertyDefinitionsContext *kU_PropertyDefinitions();
    antlr4::tree::TerminalNode *AS();
    OC_QueryContext *oC_Query();
    KU_IfNotExistsContext *kU_IfNotExists();
    KU_CreateNodeConstraintContext *kU_CreateNodeConstraint();

   
  };

  KU_CreateNodeTableContext* kU_CreateNodeTable();

  class  KU_CreateRelTableContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateRelTableContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *REL();
    antlr4::tree::TerminalNode *TABLE();
    OC_SchemaNameContext *oC_SchemaName();
    KU_FromToConnectionsContext *kU_FromToConnections();
    antlr4::tree::TerminalNode *AS();
    OC_QueryContext *oC_Query();
    antlr4::tree::TerminalNode *GROUP();
    KU_IfNotExistsContext *kU_IfNotExists();
    antlr4::tree::TerminalNode *WITH();
    KU_OptionsContext *kU_Options();
    KU_PropertyDefinitionsContext *kU_PropertyDefinitions();
    OC_SymbolicNameContext *oC_SymbolicName();

   
  };

  KU_CreateRelTableContext* kU_CreateRelTable();

  class  KU_FromToConnectionsContext : public antlr4::ParserRuleContext {
  public:
    KU_FromToConnectionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_FromToConnectionContext *> kU_FromToConnection();
    KU_FromToConnectionContext* kU_FromToConnection(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_FromToConnectionsContext* kU_FromToConnections();

  class  KU_FromToConnectionContext : public antlr4::ParserRuleContext {
  public:
    KU_FromToConnectionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *FROM();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_SchemaNameContext *> oC_SchemaName();
    OC_SchemaNameContext* oC_SchemaName(size_t i);
    antlr4::tree::TerminalNode *TO();

   
  };

  KU_FromToConnectionContext* kU_FromToConnection();

  class  KU_CreateSequenceContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateSequenceContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *SEQUENCE();
    OC_SchemaNameContext *oC_SchemaName();
    KU_IfNotExistsContext *kU_IfNotExists();
    std::vector<KU_SequenceOptionsContext *> kU_SequenceOptions();
    KU_SequenceOptionsContext* kU_SequenceOptions(size_t i);

   
  };

  KU_CreateSequenceContext* kU_CreateSequence();

  class  KU_CreateTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *TYPE();
    OC_SchemaNameContext *oC_SchemaName();
    antlr4::tree::TerminalNode *AS();
    KU_DataTypeContext *kU_DataType();

   
  };

  KU_CreateTypeContext* kU_CreateType();

  class  KU_SequenceOptionsContext : public antlr4::ParserRuleContext {
  public:
    KU_SequenceOptionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_IncrementByContext *kU_IncrementBy();
    KU_MinValueContext *kU_MinValue();
    KU_MaxValueContext *kU_MaxValue();
    KU_StartWithContext *kU_StartWith();
    KU_CycleContext *kU_Cycle();

   
  };

  KU_SequenceOptionsContext* kU_SequenceOptions();

  class  KU_WithPasswdContext : public antlr4::ParserRuleContext {
  public:
    KU_WithPasswdContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *WITH();
    antlr4::tree::TerminalNode *PASSWORD();
    antlr4::tree::TerminalNode *StringLiteral();

   
  };

  KU_WithPasswdContext* kU_WithPasswd();

  class  KU_CreateUserContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateUserContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *USER();
    OC_VariableContext *oC_Variable();
    KU_IfNotExistsContext *kU_IfNotExists();
    KU_WithPasswdContext *kU_WithPasswd();

   
  };

  KU_CreateUserContext* kU_CreateUser();

  class  KU_CreateRoleContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateRoleContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *ROLE();
    OC_VariableContext *oC_Variable();
    KU_IfNotExistsContext *kU_IfNotExists();

   
  };

  KU_CreateRoleContext* kU_CreateRole();

  class  KU_IncrementByContext : public antlr4::ParserRuleContext {
  public:
    KU_IncrementByContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *INCREMENT();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_IntegerLiteralContext *oC_IntegerLiteral();
    antlr4::tree::TerminalNode *BY();
    antlr4::tree::TerminalNode *MINUS();

   
  };

  KU_IncrementByContext* kU_IncrementBy();

  class  KU_MinValueContext : public antlr4::ParserRuleContext {
  public:
    KU_MinValueContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *NO();
    antlr4::tree::TerminalNode *SP();
    antlr4::tree::TerminalNode *MINVALUE();
    OC_IntegerLiteralContext *oC_IntegerLiteral();
    antlr4::tree::TerminalNode *MINUS();

   
  };

  KU_MinValueContext* kU_MinValue();

  class  KU_MaxValueContext : public antlr4::ParserRuleContext {
  public:
    KU_MaxValueContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *NO();
    antlr4::tree::TerminalNode *SP();
    antlr4::tree::TerminalNode *MAXVALUE();
    OC_IntegerLiteralContext *oC_IntegerLiteral();
    antlr4::tree::TerminalNode *MINUS();

   
  };

  KU_MaxValueContext* kU_MaxValue();

  class  KU_StartWithContext : public antlr4::ParserRuleContext {
  public:
    KU_StartWithContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *START();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_IntegerLiteralContext *oC_IntegerLiteral();
    antlr4::tree::TerminalNode *WITH();
    antlr4::tree::TerminalNode *MINUS();

   
  };

  KU_StartWithContext* kU_StartWith();

  class  KU_CycleContext : public antlr4::ParserRuleContext {
  public:
    KU_CycleContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CYCLE();
    antlr4::tree::TerminalNode *NO();
    antlr4::tree::TerminalNode *SP();

   
  };

  KU_CycleContext* kU_Cycle();

  class  KU_IfExistsContext : public antlr4::ParserRuleContext {
  public:
    KU_IfExistsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *IF();
    antlr4::tree::TerminalNode *SP();
    antlr4::tree::TerminalNode *EXISTS();

   
  };

  KU_IfExistsContext* kU_IfExists();

  class  KU_DropContext : public antlr4::ParserRuleContext {
  public:
    KU_DropContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DROP();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_SchemaNameContext *oC_SchemaName();
    antlr4::tree::TerminalNode *TABLE();
    antlr4::tree::TerminalNode *SEQUENCE();
    antlr4::tree::TerminalNode *MACRO();
    KU_IfExistsContext *kU_IfExists();

   
  };

  KU_DropContext* kU_Drop();

  class  KU_AlterTableContext : public antlr4::ParserRuleContext {
  public:
    KU_AlterTableContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ALTER();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *TABLE();
    OC_SchemaNameContext *oC_SchemaName();
    KU_AlterOptionsContext *kU_AlterOptions();

   
  };

  KU_AlterTableContext* kU_AlterTable();

  class  KU_AlterOptionsContext : public antlr4::ParserRuleContext {
  public:
    KU_AlterOptionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_AddPropertyContext *kU_AddProperty();
    KU_DropPropertyContext *kU_DropProperty();
    KU_RenameTableContext *kU_RenameTable();
    KU_RenamePropertyContext *kU_RenameProperty();
    KU_AddFromToConnectionContext *kU_AddFromToConnection();
    KU_DropFromToConnectionContext *kU_DropFromToConnection();

   
  };

  KU_AlterOptionsContext* kU_AlterOptions();

  class  KU_AddPropertyContext : public antlr4::ParserRuleContext {
  public:
    KU_AddPropertyContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ADD();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_PropertyKeyNameContext *oC_PropertyKeyName();
    KU_DataTypeContext *kU_DataType();
    KU_IfNotExistsContext *kU_IfNotExists();
    KU_DefaultContext *kU_Default();

   
  };

  KU_AddPropertyContext* kU_AddProperty();

  class  KU_DefaultContext : public antlr4::ParserRuleContext {
  public:
    KU_DefaultContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DEFAULT();
    antlr4::tree::TerminalNode *SP();
    OC_ExpressionContext *oC_Expression();

   
  };

  KU_DefaultContext* kU_Default();

  class  KU_DropPropertyContext : public antlr4::ParserRuleContext {
  public:
    KU_DropPropertyContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DROP();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_PropertyKeyNameContext *oC_PropertyKeyName();
    KU_IfExistsContext *kU_IfExists();

   
  };

  KU_DropPropertyContext* kU_DropProperty();

  class  KU_RenameTableContext : public antlr4::ParserRuleContext {
  public:
    KU_RenameTableContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *RENAME();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *TO();
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  KU_RenameTableContext* kU_RenameTable();

  class  KU_RenamePropertyContext : public antlr4::ParserRuleContext {
  public:
    KU_RenamePropertyContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *RENAME();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_PropertyKeyNameContext *> oC_PropertyKeyName();
    OC_PropertyKeyNameContext* oC_PropertyKeyName(size_t i);
    antlr4::tree::TerminalNode *TO();

   
  };

  KU_RenamePropertyContext* kU_RenameProperty();

  class  KU_AddFromToConnectionContext : public antlr4::ParserRuleContext {
  public:
    KU_AddFromToConnectionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ADD();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    KU_FromToConnectionContext *kU_FromToConnection();
    KU_IfNotExistsContext *kU_IfNotExists();

   
  };

  KU_AddFromToConnectionContext* kU_AddFromToConnection();

  class  KU_DropFromToConnectionContext : public antlr4::ParserRuleContext {
  public:
    KU_DropFromToConnectionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DROP();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    KU_FromToConnectionContext *kU_FromToConnection();
    KU_IfExistsContext *kU_IfExists();

   
  };

  KU_DropFromToConnectionContext* kU_DropFromToConnection();

  class  KU_ColumnDefinitionsContext : public antlr4::ParserRuleContext {
  public:
    KU_ColumnDefinitionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_ColumnDefinitionContext *> kU_ColumnDefinition();
    KU_ColumnDefinitionContext* kU_ColumnDefinition(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_ColumnDefinitionsContext* kU_ColumnDefinitions();

  class  KU_ColumnDefinitionContext : public antlr4::ParserRuleContext {
  public:
    KU_ColumnDefinitionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PropertyKeyNameContext *oC_PropertyKeyName();
    antlr4::tree::TerminalNode *SP();
    KU_DataTypeContext *kU_DataType();

   
  };

  KU_ColumnDefinitionContext* kU_ColumnDefinition();

  class  KU_PropertyDefinitionsContext : public antlr4::ParserRuleContext {
  public:
    KU_PropertyDefinitionsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_PropertyDefinitionContext *> kU_PropertyDefinition();
    KU_PropertyDefinitionContext* kU_PropertyDefinition(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_PropertyDefinitionsContext* kU_PropertyDefinitions();

  class  KU_PropertyDefinitionContext : public antlr4::ParserRuleContext {
  public:
    KU_PropertyDefinitionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_ColumnDefinitionContext *kU_ColumnDefinition();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    KU_DefaultContext *kU_Default();
    antlr4::tree::TerminalNode *PRIMARY();
    antlr4::tree::TerminalNode *KEY();

   
  };

  KU_PropertyDefinitionContext* kU_PropertyDefinition();

  class  KU_CreateNodeConstraintContext : public antlr4::ParserRuleContext {
  public:
    KU_CreateNodeConstraintContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *PRIMARY();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *KEY();
    OC_PropertyKeyNameContext *oC_PropertyKeyName();

   
  };

  KU_CreateNodeConstraintContext* kU_CreateNodeConstraint();

  class  KU_UnionTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_UnionTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UNION();
    KU_ColumnDefinitionsContext *kU_ColumnDefinitions();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_UnionTypeContext* kU_UnionType();

  class  KU_StructTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_StructTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *STRUCT();
    KU_ColumnDefinitionsContext *kU_ColumnDefinitions();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_StructTypeContext* kU_StructType();

  class  KU_MapTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_MapTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MAP();
    std::vector<KU_DataTypeContext *> kU_DataType();
    KU_DataTypeContext* kU_DataType(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_MapTypeContext* kU_MapType();

  class  KU_DecimalTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_DecimalTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DECIMAL();
    std::vector<OC_IntegerLiteralContext *> oC_IntegerLiteral();
    OC_IntegerLiteralContext* oC_IntegerLiteral(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_DecimalTypeContext* kU_DecimalType();

  class  KU_DataTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_DataTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();
    KU_UnionTypeContext *kU_UnionType();
    KU_StructTypeContext *kU_StructType();
    KU_MapTypeContext *kU_MapType();
    KU_DecimalTypeContext *kU_DecimalType();
    KU_DataTypeContext *kU_DataType();
    KU_ListIdentifiersContext *kU_ListIdentifiers();

   
  };

  KU_DataTypeContext* kU_DataType();
  KU_DataTypeContext* kU_DataType(int precedence);
  class  KU_ListIdentifiersContext : public antlr4::ParserRuleContext {
  public:
    KU_ListIdentifiersContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_ListIdentifierContext *> kU_ListIdentifier();
    KU_ListIdentifierContext* kU_ListIdentifier(size_t i);

   
  };

  KU_ListIdentifiersContext* kU_ListIdentifiers();

  class  KU_ListIdentifierContext : public antlr4::ParserRuleContext {
  public:
    KU_ListIdentifierContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_IntegerLiteralContext *oC_IntegerLiteral();

   
  };

  KU_ListIdentifierContext* kU_ListIdentifier();

  class  OC_AnyCypherOptionContext : public antlr4::ParserRuleContext {
  public:
    OC_AnyCypherOptionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ExplainContext *oC_Explain();
    OC_ProfileContext *oC_Profile();

   
  };

  OC_AnyCypherOptionContext* oC_AnyCypherOption();

  class  OC_ExplainContext : public antlr4::ParserRuleContext {
  public:
    OC_ExplainContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *EXPLAIN();
    antlr4::tree::TerminalNode *SP();
    antlr4::tree::TerminalNode *LOGICAL();

   
  };

  OC_ExplainContext* oC_Explain();

  class  OC_ProfileContext : public antlr4::ParserRuleContext {
  public:
    OC_ProfileContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *PROFILE();

   
  };

  OC_ProfileContext* oC_Profile();

  class  KU_TransactionContext : public antlr4::ParserRuleContext {
  public:
    KU_TransactionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *BEGIN();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *TRANSACTION();
    antlr4::tree::TerminalNode *READ();
    antlr4::tree::TerminalNode *ONLY();
    antlr4::tree::TerminalNode *COMMIT();
    antlr4::tree::TerminalNode *ROLLBACK();
    antlr4::tree::TerminalNode *CHECKPOINT();

   
  };

  KU_TransactionContext* kU_Transaction();

  class  KU_ExtensionContext : public antlr4::ParserRuleContext {
  public:
    KU_ExtensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_LoadExtensionContext *kU_LoadExtension();
    KU_InstallExtensionContext *kU_InstallExtension();
    KU_UninstallExtensionContext *kU_UninstallExtension();
    KU_UpdateExtensionContext *kU_UpdateExtension();

   
  };

  KU_ExtensionContext* kU_Extension();

  class  KU_LoadExtensionContext : public antlr4::ParserRuleContext {
  public:
    KU_LoadExtensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *LOAD();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *StringLiteral();
    OC_VariableContext *oC_Variable();
    antlr4::tree::TerminalNode *EXTENSION();

   
  };

  KU_LoadExtensionContext* kU_LoadExtension();

  class  KU_InstallExtensionContext : public antlr4::ParserRuleContext {
  public:
    KU_InstallExtensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *INSTALL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_VariableContext *oC_Variable();
    antlr4::tree::TerminalNode *FORCE();
    antlr4::tree::TerminalNode *FROM();
    antlr4::tree::TerminalNode *StringLiteral();

   
  };

  KU_InstallExtensionContext* kU_InstallExtension();

  class  KU_UninstallExtensionContext : public antlr4::ParserRuleContext {
  public:
    KU_UninstallExtensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UNINSTALL();
    antlr4::tree::TerminalNode *SP();
    OC_VariableContext *oC_Variable();

   
  };

  KU_UninstallExtensionContext* kU_UninstallExtension();

  class  KU_UpdateExtensionContext : public antlr4::ParserRuleContext {
  public:
    KU_UpdateExtensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UPDATE();
    antlr4::tree::TerminalNode *SP();
    OC_VariableContext *oC_Variable();

   
  };

  KU_UpdateExtensionContext* kU_UpdateExtension();

  class  OC_QueryContext : public antlr4::ParserRuleContext {
  public:
    OC_QueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_RegularQueryContext *oC_RegularQuery();

   
  };

  OC_QueryContext* oC_Query();

  class  OC_RegularQueryContext : public antlr4::ParserRuleContext {
  public:
    OC_RegularQueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SingleQueryContext *oC_SingleQuery();
    std::vector<OC_UnionContext *> oC_Union();
    OC_UnionContext* oC_Union(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_ReturnContext *> oC_Return();
    OC_ReturnContext* oC_Return(size_t i);

   
  };

  OC_RegularQueryContext* oC_RegularQuery();

  class  OC_UnionContext : public antlr4::ParserRuleContext {
  public:
    OC_UnionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UNION();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *ALL();
    OC_SingleQueryContext *oC_SingleQuery();

   
  };

  OC_UnionContext* oC_Union();

  class  OC_SingleQueryContext : public antlr4::ParserRuleContext {
  public:
    OC_SingleQueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SinglePartQueryContext *oC_SinglePartQuery();
    OC_MultiPartQueryContext *oC_MultiPartQuery();

   
  };

  OC_SingleQueryContext* oC_SingleQuery();

  class  OC_SinglePartQueryContext : public antlr4::ParserRuleContext {
  public:
    OC_SinglePartQueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ReturnContext *oC_Return();
    std::vector<OC_ReadingClauseContext *> oC_ReadingClause();
    OC_ReadingClauseContext* oC_ReadingClause(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_UpdatingClauseContext *> oC_UpdatingClause();
    OC_UpdatingClauseContext* oC_UpdatingClause(size_t i);

   
  };

  OC_SinglePartQueryContext* oC_SinglePartQuery();

  class  OC_MultiPartQueryContext : public antlr4::ParserRuleContext {
  public:
    OC_MultiPartQueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SinglePartQueryContext *oC_SinglePartQuery();
    std::vector<KU_QueryPartContext *> kU_QueryPart();
    KU_QueryPartContext* kU_QueryPart(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_MultiPartQueryContext* oC_MultiPartQuery();

  class  KU_QueryPartContext : public antlr4::ParserRuleContext {
  public:
    KU_QueryPartContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_WithContext *oC_With();
    std::vector<OC_ReadingClauseContext *> oC_ReadingClause();
    OC_ReadingClauseContext* oC_ReadingClause(size_t i);
    std::vector<OC_UpdatingClauseContext *> oC_UpdatingClause();
    OC_UpdatingClauseContext* oC_UpdatingClause(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_QueryPartContext* kU_QueryPart();

  class  OC_UpdatingClauseContext : public antlr4::ParserRuleContext {
  public:
    OC_UpdatingClauseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_CreateContext *oC_Create();
    OC_MergeContext *oC_Merge();
    OC_SetContext *oC_Set();
    OC_DeleteContext *oC_Delete();

   
  };

  OC_UpdatingClauseContext* oC_UpdatingClause();

  class  OC_ReadingClauseContext : public antlr4::ParserRuleContext {
  public:
    OC_ReadingClauseContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_MatchContext *oC_Match();
    OC_UnwindContext *oC_Unwind();
    KU_InQueryCallContext *kU_InQueryCall();
    KU_LoadFromContext *kU_LoadFrom();

   
  };

  OC_ReadingClauseContext* oC_ReadingClause();

  class  KU_LoadFromContext : public antlr4::ParserRuleContext {
  public:
    KU_LoadFromContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *LOAD();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *FROM();
    KU_ScanSourceContext *kU_ScanSource();
    antlr4::tree::TerminalNode *WITH();
    antlr4::tree::TerminalNode *HEADERS();
    KU_ColumnDefinitionsContext *kU_ColumnDefinitions();
    KU_OptionsContext *kU_Options();
    OC_WhereContext *oC_Where();

   
  };

  KU_LoadFromContext* kU_LoadFrom();

  class  OC_YieldItemContext : public antlr4::ParserRuleContext {
  public:
    OC_YieldItemContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_VariableContext *> oC_Variable();
    OC_VariableContext* oC_Variable(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *AS();

   
  };

  OC_YieldItemContext* oC_YieldItem();

  class  OC_YieldItemsContext : public antlr4::ParserRuleContext {
  public:
    OC_YieldItemsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_YieldItemContext *> oC_YieldItem();
    OC_YieldItemContext* oC_YieldItem(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_YieldItemsContext* oC_YieldItems();

  class  KU_InQueryCallContext : public antlr4::ParserRuleContext {
  public:
    KU_InQueryCallContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CALL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_FunctionInvocationContext *oC_FunctionInvocation();
    OC_WhereContext *oC_Where();
    antlr4::tree::TerminalNode *YIELD();
    OC_YieldItemsContext *oC_YieldItems();

   
  };

  KU_InQueryCallContext* kU_InQueryCall();

  class  OC_MatchContext : public antlr4::ParserRuleContext {
  public:
    OC_MatchContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MATCH();
    OC_PatternContext *oC_Pattern();
    antlr4::tree::TerminalNode *OPTIONAL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_WhereContext *oC_Where();
    KU_HintContext *kU_Hint();

   
  };

  OC_MatchContext* oC_Match();

  class  KU_HintContext : public antlr4::ParserRuleContext {
  public:
    KU_HintContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *HINT();
    antlr4::tree::TerminalNode *SP();
    KU_JoinNodeContext *kU_JoinNode();

   
  };

  KU_HintContext* kU_Hint();

  class  KU_JoinNodeContext : public antlr4::ParserRuleContext {
  public:
    KU_JoinNodeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_JoinNodeContext *> kU_JoinNode();
    KU_JoinNodeContext* kU_JoinNode(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_SchemaNameContext *> oC_SchemaName();
    OC_SchemaNameContext* oC_SchemaName(size_t i);
    antlr4::tree::TerminalNode *JOIN();
    std::vector<antlr4::tree::TerminalNode *> MULTI_JOIN();
    antlr4::tree::TerminalNode* MULTI_JOIN(size_t i);

   
  };

  KU_JoinNodeContext* kU_JoinNode();
  KU_JoinNodeContext* kU_JoinNode(int precedence);
  class  OC_UnwindContext : public antlr4::ParserRuleContext {
  public:
    OC_UnwindContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UNWIND();
    OC_ExpressionContext *oC_Expression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *AS();
    OC_VariableContext *oC_Variable();

   
  };

  OC_UnwindContext* oC_Unwind();

  class  OC_CreateContext : public antlr4::ParserRuleContext {
  public:
    OC_CreateContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *CREATE();
    OC_PatternContext *oC_Pattern();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_CreateContext* oC_Create();

  class  OC_MergeContext : public antlr4::ParserRuleContext {
  public:
    OC_MergeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MERGE();
    OC_PatternContext *oC_Pattern();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_MergeActionContext *> oC_MergeAction();
    OC_MergeActionContext* oC_MergeAction(size_t i);

   
  };

  OC_MergeContext* oC_Merge();

  class  OC_MergeActionContext : public antlr4::ParserRuleContext {
  public:
    OC_MergeActionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ON();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *MATCH();
    OC_SetContext *oC_Set();
    antlr4::tree::TerminalNode *CREATE();

   
  };

  OC_MergeActionContext* oC_MergeAction();

  class  OC_SetContext : public antlr4::ParserRuleContext {
  public:
    OC_SetContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *SET();
    std::vector<OC_SetItemContext *> oC_SetItem();
    OC_SetItemContext* oC_SetItem(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_AtomContext *oC_Atom();
    KU_PropertiesContext *kU_Properties();

   
  };

  OC_SetContext* oC_Set();

  class  OC_SetItemContext : public antlr4::ParserRuleContext {
  public:
    OC_SetItemContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PropertyExpressionContext *oC_PropertyExpression();
    OC_ExpressionContext *oC_Expression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_SetItemContext* oC_SetItem();

  class  OC_DeleteContext : public antlr4::ParserRuleContext {
  public:
    OC_DeleteContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DELETE();
    std::vector<OC_ExpressionContext *> oC_Expression();
    OC_ExpressionContext* oC_Expression(size_t i);
    antlr4::tree::TerminalNode *DETACH();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_DeleteContext* oC_Delete();

  class  OC_WithContext : public antlr4::ParserRuleContext {
  public:
    OC_WithContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *WITH();
    OC_ProjectionBodyContext *oC_ProjectionBody();
    OC_WhereContext *oC_Where();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_WithContext* oC_With();

  class  OC_ReturnContext : public antlr4::ParserRuleContext {
  public:
    OC_ReturnContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *RETURN();
    OC_ProjectionBodyContext *oC_ProjectionBody();

   
  };

  OC_ReturnContext* oC_Return();

  class  OC_ProjectionBodyContext : public antlr4::ParserRuleContext {
  public:
    OC_ProjectionBodyContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_ProjectionItemsContext *oC_ProjectionItems();
    antlr4::tree::TerminalNode *DISTINCT();
    OC_OrderContext *oC_Order();
    OC_SkipContext *oC_Skip();
    OC_LimitContext *oC_Limit();

   
  };

  OC_ProjectionBodyContext* oC_ProjectionBody();

  class  OC_ProjectionItemsContext : public antlr4::ParserRuleContext {
  public:
    OC_ProjectionItemsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *STAR();
    std::vector<OC_ProjectionItemContext *> oC_ProjectionItem();
    OC_ProjectionItemContext* oC_ProjectionItem(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_ProjectionItemsContext* oC_ProjectionItems();

  class  OC_ProjectionItemContext : public antlr4::ParserRuleContext {
  public:
    OC_ProjectionItemContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ExpressionContext *oC_Expression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *AS();
    OC_VariableContext *oC_Variable();

   
  };

  OC_ProjectionItemContext* oC_ProjectionItem();

  class  OC_OrderContext : public antlr4::ParserRuleContext {
  public:
    OC_OrderContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ORDER();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *BY();
    std::vector<OC_SortItemContext *> oC_SortItem();
    OC_SortItemContext* oC_SortItem(size_t i);

   
  };

  OC_OrderContext* oC_Order();

  class  OC_SkipContext : public antlr4::ParserRuleContext {
  public:
    OC_SkipContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *L_SKIP();
    antlr4::tree::TerminalNode *SP();
    OC_ExpressionContext *oC_Expression();

   
  };

  OC_SkipContext* oC_Skip();

  class  OC_LimitContext : public antlr4::ParserRuleContext {
  public:
    OC_LimitContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *LIMIT();
    antlr4::tree::TerminalNode *SP();
    OC_ExpressionContext *oC_Expression();

   
  };

  OC_LimitContext* oC_Limit();

  class  OC_SortItemContext : public antlr4::ParserRuleContext {
  public:
    OC_SortItemContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ExpressionContext *oC_Expression();
    antlr4::tree::TerminalNode *ASCENDING();
    antlr4::tree::TerminalNode *ASC();
    antlr4::tree::TerminalNode *DESCENDING();
    antlr4::tree::TerminalNode *DESC();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_SortItemContext* oC_SortItem();

  class  OC_WhereContext : public antlr4::ParserRuleContext {
  public:
    OC_WhereContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *WHERE();
    antlr4::tree::TerminalNode *SP();
    OC_ExpressionContext *oC_Expression();

   
  };

  OC_WhereContext* oC_Where();

  class  OC_PatternContext : public antlr4::ParserRuleContext {
  public:
    OC_PatternContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_PatternPartContext *> oC_PatternPart();
    OC_PatternPartContext* oC_PatternPart(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_PatternContext* oC_Pattern();

  class  OC_PatternPartContext : public antlr4::ParserRuleContext {
  public:
    OC_PatternPartContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_VariableContext *oC_Variable();
    OC_AnonymousPatternPartContext *oC_AnonymousPatternPart();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_PatternPartContext* oC_PatternPart();

  class  OC_AnonymousPatternPartContext : public antlr4::ParserRuleContext {
  public:
    OC_AnonymousPatternPartContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PatternElementContext *oC_PatternElement();

   
  };

  OC_AnonymousPatternPartContext* oC_AnonymousPatternPart();

  class  OC_PatternElementContext : public antlr4::ParserRuleContext {
  public:
    OC_PatternElementContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_NodePatternContext *oC_NodePattern();
    std::vector<OC_PatternElementChainContext *> oC_PatternElementChain();
    OC_PatternElementChainContext* oC_PatternElementChain(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_PatternElementContext *oC_PatternElement();

   
  };

  OC_PatternElementContext* oC_PatternElement();

  class  OC_NodePatternContext : public antlr4::ParserRuleContext {
  public:
    OC_NodePatternContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_VariableContext *oC_Variable();
    OC_NodeLabelsContext *oC_NodeLabels();
    KU_PropertiesContext *kU_Properties();

   
  };

  OC_NodePatternContext* oC_NodePattern();

  class  OC_PatternElementChainContext : public antlr4::ParserRuleContext {
  public:
    OC_PatternElementChainContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_RelationshipPatternContext *oC_RelationshipPattern();
    OC_NodePatternContext *oC_NodePattern();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_PatternElementChainContext* oC_PatternElementChain();

  class  OC_RelationshipPatternContext : public antlr4::ParserRuleContext {
  public:
    OC_RelationshipPatternContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_LeftArrowHeadContext *oC_LeftArrowHead();
    std::vector<OC_DashContext *> oC_Dash();
    OC_DashContext* oC_Dash(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_RelationshipDetailContext *oC_RelationshipDetail();
    OC_RightArrowHeadContext *oC_RightArrowHead();

   
  };

  OC_RelationshipPatternContext* oC_RelationshipPattern();

  class  OC_RelationshipDetailContext : public antlr4::ParserRuleContext {
  public:
    OC_RelationshipDetailContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_VariableContext *oC_Variable();
    OC_RelationshipTypesContext *oC_RelationshipTypes();
    KU_RecursiveDetailContext *kU_RecursiveDetail();
    KU_PropertiesContext *kU_Properties();

   
  };

  OC_RelationshipDetailContext* oC_RelationshipDetail();

  class  KU_PropertiesContext : public antlr4::ParserRuleContext {
  public:
    KU_PropertiesContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<OC_PropertyKeyNameContext *> oC_PropertyKeyName();
    OC_PropertyKeyNameContext* oC_PropertyKeyName(size_t i);
    std::vector<antlr4::tree::TerminalNode *> COLON();
    antlr4::tree::TerminalNode* COLON(size_t i);
    std::vector<OC_ExpressionContext *> oC_Expression();
    OC_ExpressionContext* oC_Expression(size_t i);

   
  };

  KU_PropertiesContext* kU_Properties();

  class  OC_RelationshipTypesContext : public antlr4::ParserRuleContext {
  public:
    OC_RelationshipTypesContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> COLON();
    antlr4::tree::TerminalNode* COLON(size_t i);
    std::vector<OC_RelTypeNameContext *> oC_RelTypeName();
    OC_RelTypeNameContext* oC_RelTypeName(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_RelationshipTypesContext* oC_RelationshipTypes();

  class  OC_NodeLabelsContext : public antlr4::ParserRuleContext {
  public:
    OC_NodeLabelsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> COLON();
    antlr4::tree::TerminalNode* COLON(size_t i);
    std::vector<OC_LabelNameContext *> oC_LabelName();
    OC_LabelNameContext* oC_LabelName(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_NodeLabelsContext* oC_NodeLabels();

  class  KU_RecursiveDetailContext : public antlr4::ParserRuleContext {
  public:
    KU_RecursiveDetailContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *STAR();
    KU_RecursiveTypeContext *kU_RecursiveType();
    OC_RangeLiteralContext *oC_RangeLiteral();
    KU_RecursiveComprehensionContext *kU_RecursiveComprehension();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_RecursiveDetailContext* kU_RecursiveDetail();

  class  KU_RecursiveTypeContext : public antlr4::ParserRuleContext {
  public:
    KU_RecursiveTypeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *WSHORTEST();
    OC_PropertyKeyNameContext *oC_PropertyKeyName();
    antlr4::tree::TerminalNode *ALL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *SHORTEST();
    antlr4::tree::TerminalNode *TRAIL();
    antlr4::tree::TerminalNode *ACYCLIC();

   
  };

  KU_RecursiveTypeContext* kU_RecursiveType();

  class  OC_RangeLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_RangeLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DOTDOT();
    OC_LowerBoundContext *oC_LowerBound();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_UpperBoundContext *oC_UpperBound();
    OC_IntegerLiteralContext *oC_IntegerLiteral();

   
  };

  OC_RangeLiteralContext* oC_RangeLiteral();

  class  KU_RecursiveComprehensionContext : public antlr4::ParserRuleContext {
  public:
    KU_RecursiveComprehensionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_VariableContext *> oC_Variable();
    OC_VariableContext* oC_Variable(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_WhereContext *oC_Where();
    std::vector<KU_RecursiveProjectionItemsContext *> kU_RecursiveProjectionItems();
    KU_RecursiveProjectionItemsContext* kU_RecursiveProjectionItems(size_t i);

   
  };

  KU_RecursiveComprehensionContext* kU_RecursiveComprehension();

  class  KU_RecursiveProjectionItemsContext : public antlr4::ParserRuleContext {
  public:
    KU_RecursiveProjectionItemsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_ProjectionItemsContext *oC_ProjectionItems();

   
  };

  KU_RecursiveProjectionItemsContext* kU_RecursiveProjectionItems();

  class  OC_LowerBoundContext : public antlr4::ParserRuleContext {
  public:
    OC_LowerBoundContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DecimalInteger();

   
  };

  OC_LowerBoundContext* oC_LowerBound();

  class  OC_UpperBoundContext : public antlr4::ParserRuleContext {
  public:
    OC_UpperBoundContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DecimalInteger();

   
  };

  OC_UpperBoundContext* oC_UpperBound();

  class  OC_LabelNameContext : public antlr4::ParserRuleContext {
  public:
    OC_LabelNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  OC_LabelNameContext* oC_LabelName();

  class  OC_RelTypeNameContext : public antlr4::ParserRuleContext {
  public:
    OC_RelTypeNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  OC_RelTypeNameContext* oC_RelTypeName();

  class  OC_ExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_ExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_OrExpressionContext *oC_OrExpression();

   
  };

  OC_ExpressionContext* oC_Expression();

  class  OC_OrExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_OrExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_XorExpressionContext *> oC_XorExpression();
    OC_XorExpressionContext* oC_XorExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<antlr4::tree::TerminalNode *> OR();
    antlr4::tree::TerminalNode* OR(size_t i);

   
  };

  OC_OrExpressionContext* oC_OrExpression();

  class  OC_XorExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_XorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_AndExpressionContext *> oC_AndExpression();
    OC_AndExpressionContext* oC_AndExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<antlr4::tree::TerminalNode *> XOR();
    antlr4::tree::TerminalNode* XOR(size_t i);

   
  };

  OC_XorExpressionContext* oC_XorExpression();

  class  OC_AndExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_AndExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_NotExpressionContext *> oC_NotExpression();
    OC_NotExpressionContext* oC_NotExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    std::vector<antlr4::tree::TerminalNode *> AND();
    antlr4::tree::TerminalNode* AND(size_t i);

   
  };

  OC_AndExpressionContext* oC_AndExpression();

  class  OC_NotExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_NotExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ComparisonExpressionContext *oC_ComparisonExpression();
    std::vector<antlr4::tree::TerminalNode *> NOT();
    antlr4::tree::TerminalNode* NOT(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_NotExpressionContext* oC_NotExpression();

  class  OC_ComparisonExpressionContext : public antlr4::ParserRuleContext {
  public:
    antlr4::Token *invalid_not_equalToken = nullptr;
    OC_ComparisonExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_BitwiseOrOperatorExpressionContext *> kU_BitwiseOrOperatorExpression();
    KU_BitwiseOrOperatorExpressionContext* kU_BitwiseOrOperatorExpression(size_t i);
    std::vector<KU_ComparisonOperatorContext *> kU_ComparisonOperator();
    KU_ComparisonOperatorContext* kU_ComparisonOperator(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *INVALID_NOT_EQUAL();

   
  };

  OC_ComparisonExpressionContext* oC_ComparisonExpression();

  class  KU_ComparisonOperatorContext : public antlr4::ParserRuleContext {
  public:
    KU_ComparisonOperatorContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;

   
  };

  KU_ComparisonOperatorContext* kU_ComparisonOperator();

  class  KU_BitwiseOrOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    KU_BitwiseOrOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_BitwiseAndOperatorExpressionContext *> kU_BitwiseAndOperatorExpression();
    KU_BitwiseAndOperatorExpressionContext* kU_BitwiseAndOperatorExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_BitwiseOrOperatorExpressionContext* kU_BitwiseOrOperatorExpression();

  class  KU_BitwiseAndOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    KU_BitwiseAndOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_BitShiftOperatorExpressionContext *> kU_BitShiftOperatorExpression();
    KU_BitShiftOperatorExpressionContext* kU_BitShiftOperatorExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_BitwiseAndOperatorExpressionContext* kU_BitwiseAndOperatorExpression();

  class  KU_BitShiftOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    KU_BitShiftOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_AddOrSubtractExpressionContext *> oC_AddOrSubtractExpression();
    OC_AddOrSubtractExpressionContext* oC_AddOrSubtractExpression(size_t i);
    std::vector<KU_BitShiftOperatorContext *> kU_BitShiftOperator();
    KU_BitShiftOperatorContext* kU_BitShiftOperator(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_BitShiftOperatorExpressionContext* kU_BitShiftOperatorExpression();

  class  KU_BitShiftOperatorContext : public antlr4::ParserRuleContext {
  public:
    KU_BitShiftOperatorContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;

   
  };

  KU_BitShiftOperatorContext* kU_BitShiftOperator();

  class  OC_AddOrSubtractExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_AddOrSubtractExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_MultiplyDivideModuloExpressionContext *> oC_MultiplyDivideModuloExpression();
    OC_MultiplyDivideModuloExpressionContext* oC_MultiplyDivideModuloExpression(size_t i);
    std::vector<KU_AddOrSubtractOperatorContext *> kU_AddOrSubtractOperator();
    KU_AddOrSubtractOperatorContext* kU_AddOrSubtractOperator(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_AddOrSubtractExpressionContext* oC_AddOrSubtractExpression();

  class  KU_AddOrSubtractOperatorContext : public antlr4::ParserRuleContext {
  public:
    KU_AddOrSubtractOperatorContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MINUS();

   
  };

  KU_AddOrSubtractOperatorContext* kU_AddOrSubtractOperator();

  class  OC_MultiplyDivideModuloExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_MultiplyDivideModuloExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_PowerOfExpressionContext *> oC_PowerOfExpression();
    OC_PowerOfExpressionContext* oC_PowerOfExpression(size_t i);
    std::vector<KU_MultiplyDivideModuloOperatorContext *> kU_MultiplyDivideModuloOperator();
    KU_MultiplyDivideModuloOperatorContext* kU_MultiplyDivideModuloOperator(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_MultiplyDivideModuloExpressionContext* oC_MultiplyDivideModuloExpression();

  class  KU_MultiplyDivideModuloOperatorContext : public antlr4::ParserRuleContext {
  public:
    KU_MultiplyDivideModuloOperatorContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *STAR();

   
  };

  KU_MultiplyDivideModuloOperatorContext* kU_MultiplyDivideModuloOperator();

  class  OC_PowerOfExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_PowerOfExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_StringListNullOperatorExpressionContext *> oC_StringListNullOperatorExpression();
    OC_StringListNullOperatorExpressionContext* oC_StringListNullOperatorExpression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_PowerOfExpressionContext* oC_PowerOfExpression();

  class  OC_StringListNullOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_StringListNullOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_UnaryAddSubtractOrFactorialExpressionContext *oC_UnaryAddSubtractOrFactorialExpression();
    OC_StringOperatorExpressionContext *oC_StringOperatorExpression();
    OC_NullOperatorExpressionContext *oC_NullOperatorExpression();
    std::vector<OC_ListOperatorExpressionContext *> oC_ListOperatorExpression();
    OC_ListOperatorExpressionContext* oC_ListOperatorExpression(size_t i);

   
  };

  OC_StringListNullOperatorExpressionContext* oC_StringListNullOperatorExpression();

  class  OC_ListOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_ListOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *IN();
    OC_PropertyOrLabelsExpressionContext *oC_PropertyOrLabelsExpression();
    std::vector<OC_ExpressionContext *> oC_Expression();
    OC_ExpressionContext* oC_Expression(size_t i);
    antlr4::tree::TerminalNode *COLON();
    antlr4::tree::TerminalNode *DOTDOT();

   
  };

  OC_ListOperatorExpressionContext* oC_ListOperatorExpression();

  class  OC_StringOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_StringOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PropertyOrLabelsExpressionContext *oC_PropertyOrLabelsExpression();
    OC_RegularExpressionContext *oC_RegularExpression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *STARTS();
    antlr4::tree::TerminalNode *WITH();
    antlr4::tree::TerminalNode *ENDS();
    antlr4::tree::TerminalNode *CONTAINS();

   
  };

  OC_StringOperatorExpressionContext* oC_StringOperatorExpression();

  class  OC_RegularExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_RegularExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_RegularExpressionContext* oC_RegularExpression();

  class  OC_NullOperatorExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_NullOperatorExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *IS();
    antlr4::tree::TerminalNode *NULL_();
    antlr4::tree::TerminalNode *NOT();

   
  };

  OC_NullOperatorExpressionContext* oC_NullOperatorExpression();

  class  OC_UnaryAddSubtractOrFactorialExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_UnaryAddSubtractOrFactorialExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PropertyOrLabelsExpressionContext *oC_PropertyOrLabelsExpression();
    std::vector<antlr4::tree::TerminalNode *> MINUS();
    antlr4::tree::TerminalNode* MINUS(size_t i);
    antlr4::tree::TerminalNode *FACTORIAL();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_UnaryAddSubtractOrFactorialExpressionContext* oC_UnaryAddSubtractOrFactorialExpression();

  class  OC_PropertyOrLabelsExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_PropertyOrLabelsExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_AtomContext *oC_Atom();
    std::vector<OC_PropertyLookupContext *> oC_PropertyLookup();
    OC_PropertyLookupContext* oC_PropertyLookup(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_PropertyOrLabelsExpressionContext* oC_PropertyOrLabelsExpression();

  class  OC_AtomContext : public antlr4::ParserRuleContext {
  public:
    OC_AtomContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_LiteralContext *oC_Literal();
    OC_ParameterContext *oC_Parameter();
    OC_CaseExpressionContext *oC_CaseExpression();
    OC_ParenthesizedExpressionContext *oC_ParenthesizedExpression();
    OC_FunctionInvocationContext *oC_FunctionInvocation();
    OC_PathPatternsContext *oC_PathPatterns();
    OC_ExistCountSubqueryContext *oC_ExistCountSubquery();
    OC_VariableContext *oC_Variable();
    OC_QuantifierContext *oC_Quantifier();

   
  };

  OC_AtomContext* oC_Atom();

  class  OC_QuantifierContext : public antlr4::ParserRuleContext {
  public:
    OC_QuantifierContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ALL();
    OC_FilterExpressionContext *oC_FilterExpression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *ANY();
    antlr4::tree::TerminalNode *NONE();
    antlr4::tree::TerminalNode *SINGLE();

   
  };

  OC_QuantifierContext* oC_Quantifier();

  class  OC_FilterExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_FilterExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_IdInCollContext *oC_IdInColl();
    antlr4::tree::TerminalNode *SP();
    OC_WhereContext *oC_Where();

   
  };

  OC_FilterExpressionContext* oC_FilterExpression();

  class  OC_IdInCollContext : public antlr4::ParserRuleContext {
  public:
    OC_IdInCollContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_VariableContext *oC_Variable();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *IN();
    OC_ExpressionContext *oC_Expression();

   
  };

  OC_IdInCollContext* oC_IdInColl();

  class  OC_LiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_LiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_NumberLiteralContext *oC_NumberLiteral();
    antlr4::tree::TerminalNode *StringLiteral();
    OC_BooleanLiteralContext *oC_BooleanLiteral();
    antlr4::tree::TerminalNode *NULL_();
    OC_ListLiteralContext *oC_ListLiteral();
    KU_StructLiteralContext *kU_StructLiteral();

   
  };

  OC_LiteralContext* oC_Literal();

  class  OC_BooleanLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_BooleanLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *TRUE();
    antlr4::tree::TerminalNode *FALSE();

   
  };

  OC_BooleanLiteralContext* oC_BooleanLiteral();

  class  OC_ListLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_ListLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_ExpressionContext *oC_Expression();
    std::vector<KU_ListEntryContext *> kU_ListEntry();
    KU_ListEntryContext* kU_ListEntry(size_t i);

   
  };

  OC_ListLiteralContext* oC_ListLiteral();

  class  KU_ListEntryContext : public antlr4::ParserRuleContext {
  public:
    KU_ListEntryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *SP();
    OC_ExpressionContext *oC_Expression();

   
  };

  KU_ListEntryContext* kU_ListEntry();

  class  KU_StructLiteralContext : public antlr4::ParserRuleContext {
  public:
    KU_StructLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<KU_StructFieldContext *> kU_StructField();
    KU_StructFieldContext* kU_StructField(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_StructLiteralContext* kU_StructLiteral();

  class  KU_StructFieldContext : public antlr4::ParserRuleContext {
  public:
    KU_StructFieldContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COLON();
    OC_ExpressionContext *oC_Expression();
    OC_SymbolicNameContext *oC_SymbolicName();
    antlr4::tree::TerminalNode *StringLiteral();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_StructFieldContext* kU_StructField();

  class  OC_ParenthesizedExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_ParenthesizedExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ExpressionContext *oC_Expression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_ParenthesizedExpressionContext* oC_ParenthesizedExpression();

  class  OC_FunctionInvocationContext : public antlr4::ParserRuleContext {
  public:
    OC_FunctionInvocationContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COUNT();
    antlr4::tree::TerminalNode *STAR();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *CAST();
    std::vector<KU_FunctionParameterContext *> kU_FunctionParameter();
    KU_FunctionParameterContext* kU_FunctionParameter(size_t i);
    antlr4::tree::TerminalNode *AS();
    KU_DataTypeContext *kU_DataType();
    OC_FunctionNameContext *oC_FunctionName();
    antlr4::tree::TerminalNode *DISTINCT();

   
  };

  OC_FunctionInvocationContext* oC_FunctionInvocation();

  class  OC_FunctionNameContext : public antlr4::ParserRuleContext {
  public:
    OC_FunctionNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();

   
  };

  OC_FunctionNameContext* oC_FunctionName();

  class  KU_FunctionParameterContext : public antlr4::ParserRuleContext {
  public:
    KU_FunctionParameterContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_ExpressionContext *oC_Expression();
    OC_SymbolicNameContext *oC_SymbolicName();
    antlr4::tree::TerminalNode *COLON();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    KU_LambdaParameterContext *kU_LambdaParameter();

   
  };

  KU_FunctionParameterContext* kU_FunctionParameter();

  class  KU_LambdaParameterContext : public antlr4::ParserRuleContext {
  public:
    KU_LambdaParameterContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    KU_LambdaVarsContext *kU_LambdaVars();
    antlr4::tree::TerminalNode *MINUS();
    OC_ExpressionContext *oC_Expression();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_LambdaParameterContext* kU_LambdaParameter();

  class  KU_LambdaVarsContext : public antlr4::ParserRuleContext {
  public:
    KU_LambdaVarsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    std::vector<OC_SymbolicNameContext *> oC_SymbolicName();
    OC_SymbolicNameContext* oC_SymbolicName(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  KU_LambdaVarsContext* kU_LambdaVars();

  class  OC_PathPatternsContext : public antlr4::ParserRuleContext {
  public:
    OC_PathPatternsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_NodePatternContext *oC_NodePattern();
    std::vector<OC_PatternElementChainContext *> oC_PatternElementChain();
    OC_PatternElementChainContext* oC_PatternElementChain(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_PathPatternsContext* oC_PathPatterns();

  class  OC_ExistCountSubqueryContext : public antlr4::ParserRuleContext {
  public:
    OC_ExistCountSubqueryContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MATCH();
    OC_PatternContext *oC_Pattern();
    antlr4::tree::TerminalNode *EXISTS();
    antlr4::tree::TerminalNode *COUNT();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    OC_WhereContext *oC_Where();
    KU_HintContext *kU_Hint();

   
  };

  OC_ExistCountSubqueryContext* oC_ExistCountSubquery();

  class  OC_PropertyLookupContext : public antlr4::ParserRuleContext {
  public:
    OC_PropertyLookupContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_PropertyKeyNameContext *oC_PropertyKeyName();
    antlr4::tree::TerminalNode *STAR();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_PropertyLookupContext* oC_PropertyLookup();

  class  OC_CaseExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_CaseExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *END();
    antlr4::tree::TerminalNode *ELSE();
    std::vector<OC_ExpressionContext *> oC_Expression();
    OC_ExpressionContext* oC_Expression(size_t i);
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);
    antlr4::tree::TerminalNode *CASE();
    std::vector<OC_CaseAlternativeContext *> oC_CaseAlternative();
    OC_CaseAlternativeContext* oC_CaseAlternative(size_t i);

   
  };

  OC_CaseExpressionContext* oC_CaseExpression();

  class  OC_CaseAlternativeContext : public antlr4::ParserRuleContext {
  public:
    OC_CaseAlternativeContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *WHEN();
    std::vector<OC_ExpressionContext *> oC_Expression();
    OC_ExpressionContext* oC_Expression(size_t i);
    antlr4::tree::TerminalNode *THEN();
    std::vector<antlr4::tree::TerminalNode *> SP();
    antlr4::tree::TerminalNode* SP(size_t i);

   
  };

  OC_CaseAlternativeContext* oC_CaseAlternative();

  class  OC_VariableContext : public antlr4::ParserRuleContext {
  public:
    OC_VariableContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();

   
  };

  OC_VariableContext* oC_Variable();

  class  OC_NumberLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_NumberLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_DoubleLiteralContext *oC_DoubleLiteral();
    OC_IntegerLiteralContext *oC_IntegerLiteral();

   
  };

  OC_NumberLiteralContext* oC_NumberLiteral();

  class  OC_ParameterContext : public antlr4::ParserRuleContext {
  public:
    OC_ParameterContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();
    antlr4::tree::TerminalNode *DecimalInteger();

   
  };

  OC_ParameterContext* oC_Parameter();

  class  OC_PropertyExpressionContext : public antlr4::ParserRuleContext {
  public:
    OC_PropertyExpressionContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_AtomContext *oC_Atom();
    OC_PropertyLookupContext *oC_PropertyLookup();
    antlr4::tree::TerminalNode *SP();

   
  };

  OC_PropertyExpressionContext* oC_PropertyExpression();

  class  OC_PropertyKeyNameContext : public antlr4::ParserRuleContext {
  public:
    OC_PropertyKeyNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SchemaNameContext *oC_SchemaName();

   
  };

  OC_PropertyKeyNameContext* oC_PropertyKeyName();

  class  OC_IntegerLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_IntegerLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *DecimalInteger();

   
  };

  OC_IntegerLiteralContext* oC_IntegerLiteral();

  class  OC_DoubleLiteralContext : public antlr4::ParserRuleContext {
  public:
    OC_DoubleLiteralContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *ExponentDecimalReal();
    antlr4::tree::TerminalNode *RegularDecimalReal();

   
  };

  OC_DoubleLiteralContext* oC_DoubleLiteral();

  class  OC_SchemaNameContext : public antlr4::ParserRuleContext {
  public:
    OC_SchemaNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    OC_SymbolicNameContext *oC_SymbolicName();

   
  };

  OC_SchemaNameContext* oC_SchemaName();

  class  OC_SymbolicNameContext : public antlr4::ParserRuleContext {
  public:
    antlr4::Token *escapedsymbolicnameToken = nullptr;
    OC_SymbolicNameContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *UnescapedSymbolicName();
    antlr4::tree::TerminalNode *EscapedSymbolicName();
    antlr4::tree::TerminalNode *HexLetter();
    KU_NonReservedKeywordsContext *kU_NonReservedKeywords();

   
  };

  OC_SymbolicNameContext* oC_SymbolicName();

  class  KU_NonReservedKeywordsContext : public antlr4::ParserRuleContext {
  public:
    KU_NonReservedKeywordsContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *COMMENT();
    antlr4::tree::TerminalNode *ADD();
    antlr4::tree::TerminalNode *ALTER();
    antlr4::tree::TerminalNode *AS();
    antlr4::tree::TerminalNode *ATTACH();
    antlr4::tree::TerminalNode *BEGIN();
    antlr4::tree::TerminalNode *BY();
    antlr4::tree::TerminalNode *CALL();
    antlr4::tree::TerminalNode *CHECKPOINT();
    antlr4::tree::TerminalNode *COMMIT();
    antlr4::tree::TerminalNode *CONTAINS();
    antlr4::tree::TerminalNode *COPY();
    antlr4::tree::TerminalNode *COUNT();
    antlr4::tree::TerminalNode *CYCLE();
    antlr4::tree::TerminalNode *DATABASE();
    antlr4::tree::TerminalNode *DECIMAL();
    antlr4::tree::TerminalNode *DELETE();
    antlr4::tree::TerminalNode *DETACH();
    antlr4::tree::TerminalNode *DROP();
    antlr4::tree::TerminalNode *EXPLAIN();
    antlr4::tree::TerminalNode *EXPORT();
    antlr4::tree::TerminalNode *EXTENSION();
    antlr4::tree::TerminalNode *FORCE();
    antlr4::tree::TerminalNode *GRAPH();
    antlr4::tree::TerminalNode *IF();
    antlr4::tree::TerminalNode *IS();
    antlr4::tree::TerminalNode *IMPORT();
    antlr4::tree::TerminalNode *INCREMENT();
    antlr4::tree::TerminalNode *KEY();
    antlr4::tree::TerminalNode *LOAD();
    antlr4::tree::TerminalNode *LOGICAL();
    antlr4::tree::TerminalNode *MATCH();
    antlr4::tree::TerminalNode *MAXVALUE();
    antlr4::tree::TerminalNode *MERGE();
    antlr4::tree::TerminalNode *MINVALUE();
    antlr4::tree::TerminalNode *NO();
    antlr4::tree::TerminalNode *NODE();
    antlr4::tree::TerminalNode *PROJECT();
    antlr4::tree::TerminalNode *READ();
    antlr4::tree::TerminalNode *REL();
    antlr4::tree::TerminalNode *RENAME();
    antlr4::tree::TerminalNode *RETURN();
    antlr4::tree::TerminalNode *ROLLBACK();
    antlr4::tree::TerminalNode *ROLE();
    antlr4::tree::TerminalNode *SEQUENCE();
    antlr4::tree::TerminalNode *SET();
    antlr4::tree::TerminalNode *START();
    antlr4::tree::TerminalNode *STRUCT();
    antlr4::tree::TerminalNode *L_SKIP();
    antlr4::tree::TerminalNode *LIMIT();
    antlr4::tree::TerminalNode *TRANSACTION();
    antlr4::tree::TerminalNode *TYPE();
    antlr4::tree::TerminalNode *USE();
    antlr4::tree::TerminalNode *UNINSTALL();
    antlr4::tree::TerminalNode *UPDATE();
    antlr4::tree::TerminalNode *WRITE();
    antlr4::tree::TerminalNode *FROM();
    antlr4::tree::TerminalNode *TO();
    antlr4::tree::TerminalNode *YIELD();
    antlr4::tree::TerminalNode *USER();
    antlr4::tree::TerminalNode *PASSWORD();
    antlr4::tree::TerminalNode *MAP();

   
  };

  KU_NonReservedKeywordsContext* kU_NonReservedKeywords();

  class  OC_LeftArrowHeadContext : public antlr4::ParserRuleContext {
  public:
    OC_LeftArrowHeadContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;

   
  };

  OC_LeftArrowHeadContext* oC_LeftArrowHead();

  class  OC_RightArrowHeadContext : public antlr4::ParserRuleContext {
  public:
    OC_RightArrowHeadContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;

   
  };

  OC_RightArrowHeadContext* oC_RightArrowHead();

  class  OC_DashContext : public antlr4::ParserRuleContext {
  public:
    OC_DashContext(antlr4::ParserRuleContext *parent, size_t invokingState);
    virtual size_t getRuleIndex() const override;
    antlr4::tree::TerminalNode *MINUS();

   
  };

  OC_DashContext* oC_Dash();


  bool sempred(antlr4::RuleContext *_localctx, size_t ruleIndex, size_t predicateIndex) override;

  bool kU_DataTypeSempred(KU_DataTypeContext *_localctx, size_t predicateIndex);
  bool kU_JoinNodeSempred(KU_JoinNodeContext *_localctx, size_t predicateIndex);

  // By default the static state used to implement the parser is lazily initialized during the first
  // call to the constructor. You can call this function if you wish to initialize the static state
  // ahead of time.
  static void initialize();

private:

      virtual void notifyQueryNotConcludeWithReturn(antlr4::Token* startToken) {};
      virtual void notifyNodePatternWithoutParentheses(std::string nodeName, antlr4::Token* startToken) {};
      virtual void notifyInvalidNotEqualOperator(antlr4::Token* startToken) {};
      virtual void notifyEmptyToken(antlr4::Token* startToken) {};
      virtual void notifyReturnNotAtEnd(antlr4::Token* startToken) {};
      virtual void notifyNonBinaryComparison(antlr4::Token* startToken) {};

};

