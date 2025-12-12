
ku_Statements
    : oC_Cypher ( SP? ';' SP? oC_Cypher )* SP? EOF ;

oC_Cypher
    : oC_AnyCypherOption? SP? ( oC_Statement ) ( SP? ';' )?;

oC_Statement
    : oC_Query
        | kU_CreateUser
        | kU_CreateRole
        | kU_CreateNodeTable
        | kU_CreateRelTable
        | kU_CreateSequence
        | kU_CreateType
        | kU_Drop
        | kU_AlterTable
        | kU_CopyFrom
        | kU_CopyFromByColumn
        | kU_CopyTO
        | kU_StandaloneCall
        | kU_CreateMacro
        | kU_CommentOn
        | kU_Transaction
        | kU_Extension
        | kU_ExportDatabase
        | kU_ImportDatabase
        | kU_AttachDatabase
        | kU_DetachDatabase
        | kU_UseDatabase;

kU_CopyFrom
    : COPY SP oC_SchemaName kU_ColumnNames? SP FROM SP kU_ScanSource ( SP? '(' SP? kU_Options SP? ')' )? ;

kU_ColumnNames
    : SP? '(' SP? (oC_SchemaName ( SP? ',' SP? oC_SchemaName )* SP?)? ')';

kU_ScanSource
    : kU_FilePaths
        | '(' SP? oC_Query SP? ')'
        | oC_Parameter
        | oC_Variable
        | oC_Variable '.' SP? oC_SchemaName
        | oC_FunctionInvocation ;

kU_CopyFromByColumn
    : COPY SP oC_SchemaName SP FROM SP '(' SP? StringLiteral ( SP? ',' SP? StringLiteral )* ')' SP BY SP COLUMN ;

kU_CopyTO
    : COPY SP '(' SP? oC_Query SP? ')' SP TO SP StringLiteral ( SP? '(' SP? kU_Options SP? ')' )? ;

kU_ExportDatabase
    : EXPORT SP DATABASE SP StringLiteral ( SP? '(' SP? kU_Options SP? ')' )? ;

kU_ImportDatabase
    : IMPORT SP DATABASE SP StringLiteral;

kU_AttachDatabase
    : ATTACH SP StringLiteral (SP AS SP oC_SchemaName)? SP '(' SP? DBTYPE SP oC_SymbolicName (SP? ',' SP? kU_Options)? SP? ')' ;

kU_Option
    : oC_SymbolicName (SP? '=' SP? | SP*) oC_Literal | oC_SymbolicName;

kU_Options
    : kU_Option ( SP? ',' SP? kU_Option )* ;

kU_DetachDatabase
    : DETACH SP oC_SchemaName;

kU_UseDatabase
    : USE SP oC_SchemaName;

kU_StandaloneCall
    : CALL SP oC_SymbolicName SP? '=' SP? oC_Expression
        | CALL SP oC_FunctionInvocation;

kU_CommentOn
    : COMMENT SP ON SP TABLE SP oC_SchemaName SP IS SP StringLiteral ;

kU_CreateMacro
    : CREATE SP MACRO SP oC_FunctionName SP? '(' SP? kU_PositionalArgs? SP? kU_DefaultArg? ( SP? ',' SP? kU_DefaultArg )* SP? ')' SP AS SP oC_Expression ;

kU_PositionalArgs
    : oC_SymbolicName ( SP? ',' SP? oC_SymbolicName )* ;

kU_DefaultArg
    : oC_SymbolicName SP? ':' '=' SP? oC_Literal ;

kU_FilePaths
    : '[' SP? StringLiteral ( SP? ',' SP? StringLiteral )* ']'
        | StringLiteral
        | GLOB SP? '(' SP? StringLiteral SP? ')' ;

kU_IfNotExists
    : IF SP NOT SP EXISTS ;

kU_CreateNodeTable
    : CREATE SP NODE SP TABLE SP (kU_IfNotExists SP)? oC_SchemaName ( SP? '(' SP? kU_PropertyDefinitions SP? ( ',' SP? kU_CreateNodeConstraint )? SP? ')' | SP AS SP oC_Query ) ;

kU_CreateRelTable
    : CREATE SP REL SP TABLE ( SP GROUP )? ( SP kU_IfNotExists )? SP oC_SchemaName
        SP? '(' SP?
            kU_FromToConnections SP? (
            ( ',' SP? kU_PropertyDefinitions SP? )?
            ( ',' SP? oC_SymbolicName SP? )? // Constraints
            ')'
            | ')' SP AS SP oC_Query )
         ( SP WITH SP? '(' SP? kU_Options SP? ')')? ;

kU_FromToConnections
    : kU_FromToConnection ( SP? ',' SP? kU_FromToConnection )* ;

kU_FromToConnection
    : FROM SP oC_SchemaName SP TO SP oC_SchemaName ;

kU_CreateSequence
    : CREATE SP SEQUENCE SP (kU_IfNotExists SP)? oC_SchemaName (SP kU_SequenceOptions)* ;

kU_CreateType
    : CREATE SP TYPE SP oC_SchemaName SP AS SP kU_DataType SP? ;

kU_SequenceOptions
    : kU_IncrementBy
        | kU_MinValue
        | kU_MaxValue
        | kU_StartWith
        | kU_Cycle;

kU_WithPasswd
    : SP WITH SP PASSWORD SP StringLiteral ;

kU_CreateUser
    : CREATE SP USER SP (kU_IfNotExists SP)? oC_Variable kU_WithPasswd? ;

kU_CreateRole
    : CREATE SP ROLE SP (kU_IfNotExists SP)? oC_Variable ;

kU_IncrementBy : INCREMENT SP ( BY SP )? MINUS? oC_IntegerLiteral ;

kU_MinValue : (NO SP MINVALUE) | (MINVALUE SP MINUS? oC_IntegerLiteral) ;

kU_MaxValue : (NO SP MAXVALUE) | (MAXVALUE SP MINUS? oC_IntegerLiteral) ;

kU_StartWith : START SP ( WITH SP )? MINUS? oC_IntegerLiteral ;

kU_Cycle : (NO SP)? CYCLE ;

kU_IfExists
    : IF SP EXISTS ;

kU_Drop
    : DROP SP (TABLE | SEQUENCE | MACRO) SP (kU_IfExists SP)? oC_SchemaName ;

kU_AlterTable
    : ALTER SP TABLE SP oC_SchemaName SP kU_AlterOptions ;

kU_AlterOptions
    : kU_AddProperty
        | kU_DropProperty
        | kU_RenameTable
        | kU_RenameProperty
        | kU_AddFromToConnection
        | kU_DropFromToConnection;

kU_AddProperty
    : ADD SP (kU_IfNotExists SP)? oC_PropertyKeyName SP kU_DataType ( SP kU_Default )? ;

kU_Default
    : DEFAULT SP oC_Expression ;

kU_DropProperty
    : DROP SP (kU_IfExists SP)? oC_PropertyKeyName ;

kU_RenameTable
    : RENAME SP TO SP oC_SchemaName ;

kU_RenameProperty
    : RENAME SP oC_PropertyKeyName SP TO SP oC_PropertyKeyName ;

kU_AddFromToConnection
    : ADD SP (kU_IfNotExists SP)? kU_FromToConnection ;

kU_DropFromToConnection
    : DROP SP (kU_IfExists SP)? kU_FromToConnection ;

kU_ColumnDefinitions: kU_ColumnDefinition ( SP? ',' SP? kU_ColumnDefinition )* ;

kU_ColumnDefinition : oC_PropertyKeyName SP kU_DataType ;

kU_PropertyDefinitions : kU_PropertyDefinition ( SP? ',' SP? kU_PropertyDefinition )* ;

kU_PropertyDefinition : kU_ColumnDefinition ( SP kU_Default )? ( SP PRIMARY SP KEY)?;

kU_CreateNodeConstraint : PRIMARY SP KEY SP? '(' SP? oC_PropertyKeyName SP? ')' ;

DECIMAL: ( 'D' | 'd' ) ( 'E' | 'e' ) ( 'C' | 'c' ) ( 'I' | 'i' ) ( 'M' | 'm' ) ( 'A' | 'a' ) ( 'L' | 'l' ) ;

kU_UnionType
    : UNION SP? '(' SP? kU_ColumnDefinitions SP? ')' ;

kU_StructType
    : STRUCT SP? '(' SP? kU_ColumnDefinitions SP? ')' ;

kU_MapType
    : MAP SP? '(' SP? kU_DataType SP? ',' SP? kU_DataType SP? ')' ;

kU_DecimalType
    : DECIMAL SP? '(' SP? oC_IntegerLiteral SP? ',' SP? oC_IntegerLiteral SP? ')' ;

kU_DataType
    : oC_SymbolicName
        | kU_DataType kU_ListIdentifiers
        | kU_UnionType
        | kU_StructType
        | kU_MapType
        | kU_DecimalType ;

kU_ListIdentifiers : kU_ListIdentifier ( kU_ListIdentifier )* ;

kU_ListIdentifier : '[' oC_IntegerLiteral? ']' ;

oC_AnyCypherOption
    : oC_Explain
        | oC_Profile ;

oC_Explain
    : EXPLAIN (SP LOGICAL)? ;

oC_Profile
    : PROFILE ;

kU_Transaction
    : BEGIN SP TRANSACTION
        | BEGIN SP TRANSACTION SP READ SP ONLY
        | COMMIT
        | ROLLBACK
        | CHECKPOINT;

kU_Extension
    : kU_LoadExtension
        | kU_InstallExtension
        | kU_UninstallExtension
        | kU_UpdateExtension ;

kU_LoadExtension
    : LOAD SP (EXTENSION SP)? ( StringLiteral | oC_Variable ) ;

kU_InstallExtension
    : (FORCE SP)? INSTALL SP oC_Variable (SP FROM SP StringLiteral)?;

kU_UninstallExtension
    : UNINSTALL SP oC_Variable;

kU_UpdateExtension
    : UPDATE SP oC_Variable;

oC_Query
    : oC_RegularQuery ;

oC_RegularQuery
    : oC_SingleQuery ( SP? oC_Union )*
        | (oC_Return SP? )+ oC_SingleQuery { notifyReturnNotAtEnd($ctx->start); }
        ;

oC_Union
     :  ( UNION SP ALL SP? oC_SingleQuery )
         | ( UNION SP? oC_SingleQuery ) ;

oC_SingleQuery
    : oC_SinglePartQuery
        | oC_MultiPartQuery
        ;

oC_SinglePartQuery
    : ( oC_ReadingClause SP? )* oC_Return
        | ( ( oC_ReadingClause SP? )* oC_UpdatingClause ( SP? oC_UpdatingClause )* ( SP? oC_Return )? )
        ;

oC_MultiPartQuery
    : ( kU_QueryPart SP? )+ oC_SinglePartQuery;

kU_QueryPart
    : (oC_ReadingClause SP? )* ( oC_UpdatingClause SP? )* oC_With ;

oC_UpdatingClause
    : oC_Create
        | oC_Merge
        | oC_Set
        | oC_Delete
        ;

oC_ReadingClause
    : oC_Match
        | oC_Unwind
        | kU_InQueryCall
        | kU_LoadFrom
        ;

kU_LoadFrom
    :  LOAD ( SP WITH SP HEADERS SP? '(' SP? kU_ColumnDefinitions SP? ')' )? SP FROM SP kU_ScanSource (SP? '(' SP? kU_Options SP? ')')? (SP? oC_Where)? ;


oC_YieldItem
         :  ( oC_Variable SP AS SP )? oC_Variable ;

oC_YieldItems
          :  oC_YieldItem ( SP? ',' SP? oC_YieldItem )* ;

kU_InQueryCall
    : CALL SP oC_FunctionInvocation (SP? oC_Where)? ( SP? YIELD SP oC_YieldItems )? ;

oC_Match
    : ( OPTIONAL SP )? MATCH SP? oC_Pattern ( SP oC_Where )? ( SP kU_Hint )? ;

kU_Hint
    : HINT SP kU_JoinNode;

kU_JoinNode
    :  kU_JoinNode SP JOIN SP kU_JoinNode
        | kU_JoinNode ( SP MULTI_JOIN SP oC_SchemaName)+
        | '(' SP? kU_JoinNode SP? ')'
        | oC_SchemaName ;

oC_Unwind : UNWIND SP? oC_Expression SP AS SP oC_Variable ;

oC_Create
    : CREATE SP? oC_Pattern ;

// For unknown reason, openCypher use oC_PatternPart instead of oC_Pattern. There should be no difference in terms of planning.
// So we choose to be consistent with oC_Create and use oC_Pattern instead.
oC_Merge : MERGE SP? oC_Pattern ( SP oC_MergeAction )* ;

oC_MergeAction
    :  ( ON SP MATCH SP oC_Set )
        | ( ON SP CREATE SP oC_Set )
        ;

oC_Set
    : SET SP? oC_SetItem ( SP? ',' SP? oC_SetItem )*
        | SET SP? oC_Atom SP? '=' SP? kU_Properties;

oC_SetItem
    : ( oC_PropertyExpression SP? '=' SP? oC_Expression ) ;

oC_Delete
    : ( DETACH SP )? DELETE SP? oC_Expression ( SP? ',' SP? oC_Expression )*;

oC_With
    : WITH oC_ProjectionBody ( SP? oC_Where )? ;

oC_Return
    : RETURN oC_ProjectionBody ;

oC_ProjectionBody
    : ( SP? DISTINCT )? SP oC_ProjectionItems (SP oC_Order )? ( SP oC_Skip )? ( SP oC_Limit )? ;

oC_ProjectionItems
    : ( STAR ( SP? ',' SP? oC_ProjectionItem )* )
        | ( oC_ProjectionItem ( SP? ',' SP? oC_ProjectionItem )* )
        ;

STAR : '*' ;

oC_ProjectionItem
    : ( oC_Expression SP AS SP oC_Variable )
        | oC_Expression
        ;

oC_Order
    : ORDER SP BY SP oC_SortItem ( ',' SP? oC_SortItem )* ;

oC_Skip
    :  L_SKIP SP oC_Expression ;

L_SKIP : ( 'S' | 's' ) ( 'K' | 'k' ) ( 'I' | 'i' ) ( 'P' | 'p' ) ;

oC_Limit
    : LIMIT SP oC_Expression ;

oC_SortItem
    : oC_Expression ( SP? ( ASCENDING | ASC | DESCENDING | DESC ) )? ;

oC_Where
    : WHERE SP oC_Expression ;

oC_Pattern
    : oC_PatternPart ( SP? ',' SP? oC_PatternPart )* ;

oC_PatternPart
    :  ( oC_Variable SP? '=' SP? oC_AnonymousPatternPart )
        | oC_AnonymousPatternPart ;

oC_AnonymousPatternPart
    : oC_PatternElement ;

oC_PatternElement
    : ( oC_NodePattern ( SP? oC_PatternElementChain )* )
        | ( '(' oC_PatternElement ')' )
        ;

oC_NodePattern
    : '(' SP? ( oC_Variable SP? )? ( oC_NodeLabels SP? )? ( kU_Properties SP? )? ')' ;

oC_PatternElementChain
    : oC_RelationshipPattern SP? oC_NodePattern ;

oC_RelationshipPattern
    : ( oC_LeftArrowHead SP? oC_Dash SP? oC_RelationshipDetail? SP? oC_Dash )
        | ( oC_Dash SP? oC_RelationshipDetail? SP? oC_Dash SP? oC_RightArrowHead )
        | ( oC_Dash SP? oC_RelationshipDetail? SP? oC_Dash )
        ;

oC_RelationshipDetail
    : '[' SP? ( oC_Variable SP? )? ( oC_RelationshipTypes SP? )? ( kU_RecursiveDetail SP? )? ( kU_Properties SP? )? ']' ;

// The original oC_Properties definition is  oC_MapLiteral | oC_Parameter.
// We choose to not support parameter as properties which will be the decision for a long time.
// We then substitute with oC_MapLiteral definition. We create oC_MapLiteral only when we decide to add MAP type.
kU_Properties
    : '{' SP? ( oC_PropertyKeyName SP? ':' SP? oC_Expression SP? ( ',' SP? oC_PropertyKeyName SP? ':' SP? oC_Expression SP? )* )? '}';

oC_RelationshipTypes
    :  ':' SP? oC_RelTypeName ( SP? '|' ':'? SP? oC_RelTypeName )* ;

oC_NodeLabels
    :  ':' SP? oC_LabelName ( SP? ('|' ':'? | ':') SP? oC_LabelName )* ;

kU_RecursiveDetail
    : '*' ( SP? kU_RecursiveType)? ( SP? oC_RangeLiteral )? ( SP? kU_RecursiveComprehension )? ;

kU_RecursiveType
    : (ALL SP)? WSHORTEST SP? '(' SP? oC_PropertyKeyName SP? ')'
        | SHORTEST
        | ALL SP SHORTEST
        | TRAIL
        | ACYCLIC ;

oC_RangeLiteral
    :  oC_LowerBound? SP? DOTDOT SP? oC_UpperBound?
        | oC_IntegerLiteral ;

kU_RecursiveComprehension
    : '(' SP? oC_Variable SP? ',' SP? oC_Variable ( SP? '|' SP? oC_Where SP? )? ( SP? '|' SP? kU_RecursiveProjectionItems SP? ',' SP? kU_RecursiveProjectionItems SP? )? ')' ;

kU_RecursiveProjectionItems
    : '{' SP? oC_ProjectionItems? SP? '}' ;

oC_LowerBound
    : DecimalInteger ;

oC_UpperBound
    : DecimalInteger ;

oC_LabelName
    : oC_SchemaName ;

oC_RelTypeName
    : oC_SchemaName ;

oC_Expression
    : oC_OrExpression ;

oC_OrExpression
    : oC_XorExpression ( SP OR SP oC_XorExpression )* ;

oC_XorExpression
    : oC_AndExpression ( SP XOR SP oC_AndExpression )* ;

oC_AndExpression
    : oC_NotExpression ( SP AND SP oC_NotExpression )* ;

oC_NotExpression
    : ( NOT SP? )*  oC_ComparisonExpression;

oC_ComparisonExpression
    : kU_BitwiseOrOperatorExpression ( SP? kU_ComparisonOperator SP? kU_BitwiseOrOperatorExpression )?
        | kU_BitwiseOrOperatorExpression ( SP? INVALID_NOT_EQUAL SP? kU_BitwiseOrOperatorExpression ) { notifyInvalidNotEqualOperator($INVALID_NOT_EQUAL); }
        | kU_BitwiseOrOperatorExpression SP? kU_ComparisonOperator SP? kU_BitwiseOrOperatorExpression ( SP? kU_ComparisonOperator SP? kU_BitwiseOrOperatorExpression )+ { notifyNonBinaryComparison($ctx->start); }
        ;

kU_ComparisonOperator : '=' | '<>' | '<' | '<=' | '>' | '>=' ;

INVALID_NOT_EQUAL : '!=' ;

kU_BitwiseOrOperatorExpression
    : kU_BitwiseAndOperatorExpression ( SP? '|' SP? kU_BitwiseAndOperatorExpression )* ;

kU_BitwiseAndOperatorExpression
    : kU_BitShiftOperatorExpression ( SP? '&' SP? kU_BitShiftOperatorExpression )* ;

kU_BitShiftOperatorExpression
    : oC_AddOrSubtractExpression ( SP? kU_BitShiftOperator SP? oC_AddOrSubtractExpression )* ;

kU_BitShiftOperator : '>>' | '<<' ;

oC_AddOrSubtractExpression
    : oC_MultiplyDivideModuloExpression ( SP? kU_AddOrSubtractOperator SP? oC_MultiplyDivideModuloExpression )* ;

kU_AddOrSubtractOperator : '+' | '-' ;

oC_MultiplyDivideModuloExpression
    : oC_PowerOfExpression ( SP? kU_MultiplyDivideModuloOperator SP? oC_PowerOfExpression )* ;

kU_MultiplyDivideModuloOperator : '*' | '/' | '%' ;

oC_PowerOfExpression
    : oC_StringListNullOperatorExpression ( SP? '^' SP? oC_StringListNullOperatorExpression )* ;

oC_StringListNullOperatorExpression
    : oC_UnaryAddSubtractOrFactorialExpression ( oC_StringOperatorExpression | oC_ListOperatorExpression+ | oC_NullOperatorExpression )? ;

oC_ListOperatorExpression
    : ( SP IN SP? oC_PropertyOrLabelsExpression )
        | ( '[' oC_Expression ']' )
        | ( '[' oC_Expression? ( COLON | DOTDOT ) oC_Expression? ']' ) ;

COLON : ':' ;

DOTDOT : '..' ;

oC_StringOperatorExpression
    :  ( oC_RegularExpression | ( SP STARTS SP WITH ) | ( SP ENDS SP WITH ) | ( SP CONTAINS ) ) SP? oC_PropertyOrLabelsExpression ;

oC_RegularExpression
    :  SP? '=~' ;

oC_NullOperatorExpression
    : ( SP IS SP NULL )
        | ( SP IS SP NOT SP NULL ) ;

MINUS : '-' ;

FACTORIAL : '!' ;

oC_UnaryAddSubtractOrFactorialExpression
    : ( MINUS SP? )* oC_PropertyOrLabelsExpression (SP? FACTORIAL)? ;

oC_PropertyOrLabelsExpression
    : oC_Atom ( SP? oC_PropertyLookup )* ;

oC_Atom
    : oC_Literal
        | oC_Parameter
        | oC_CaseExpression
        | oC_ParenthesizedExpression
        | oC_FunctionInvocation
        | oC_PathPatterns
        | oC_ExistCountSubquery
        | oC_Variable
        | oC_Quantifier
        ;

oC_Quantifier
    :  ( ALL SP? '(' SP? oC_FilterExpression SP? ')' )
        | ( ANY SP? '(' SP? oC_FilterExpression SP? ')' )
        | ( NONE SP? '(' SP? oC_FilterExpression SP? ')' )
        | ( SINGLE SP? '(' SP? oC_FilterExpression SP? ')' )
        ;

oC_FilterExpression
    :  oC_IdInColl SP oC_Where ;

oC_IdInColl
    :  oC_Variable SP IN SP oC_Expression ;

oC_Literal
    : oC_NumberLiteral
        | StringLiteral
        | oC_BooleanLiteral
        | NULL
        | oC_ListLiteral
        | kU_StructLiteral
        ;

oC_BooleanLiteral
    : TRUE
        | FALSE
        ;

oC_ListLiteral
    :  '[' SP? ( oC_Expression SP? ( kU_ListEntry SP? )* )? ']' ;

kU_ListEntry
    : ',' SP? oC_Expression? ;

kU_StructLiteral
    :  '{' SP? kU_StructField SP? ( ',' SP? kU_StructField SP? )* '}' ;

kU_StructField
    :   ( oC_SymbolicName | StringLiteral ) SP? ':' SP? oC_Expression ;

oC_ParenthesizedExpression
    : '(' SP? oC_Expression SP? ')' ;

oC_FunctionInvocation
    : COUNT SP? '(' SP? '*' SP? ')'
        | CAST SP? '(' SP? kU_FunctionParameter SP? ( ( AS SP? kU_DataType ) | ( ',' SP? kU_FunctionParameter ) ) SP? ')'
        | oC_FunctionName SP? '(' SP? ( DISTINCT SP? )? ( kU_FunctionParameter SP? ( ',' SP? kU_FunctionParameter SP? )* )? ')' ;

oC_FunctionName
    : oC_SymbolicName ;

kU_FunctionParameter
    : ( oC_SymbolicName SP? ':' '=' SP? )? oC_Expression
        | kU_LambdaParameter ;

kU_LambdaParameter
    : kU_LambdaVars SP? '-' '>' SP? oC_Expression SP? ;

kU_LambdaVars
    : oC_SymbolicName
    | '(' SP? oC_SymbolicName SP? ( ',' SP? oC_SymbolicName SP?)* ')' ;

oC_PathPatterns
    : oC_NodePattern ( SP? oC_PatternElementChain )+;

oC_ExistCountSubquery
    : (EXISTS | COUNT) SP? '{' SP? MATCH SP? oC_Pattern ( SP? oC_Where )? ( SP? kU_Hint )? SP? '}' ;

oC_PropertyLookup
    : '.' SP? ( oC_PropertyKeyName | STAR ) ;

oC_CaseExpression
    :  ( ( CASE ( SP? oC_CaseAlternative )+ ) | ( CASE SP? oC_Expression ( SP? oC_CaseAlternative )+ ) ) ( SP? ELSE SP? oC_Expression )? SP? END ;

oC_CaseAlternative
    :  WHEN SP? oC_Expression SP? THEN SP? oC_Expression ;

oC_Variable
    : oC_SymbolicName ;

StringLiteral
    : ( '"' ( StringLiteral_0 | EscapedChar )* '"' )
        | ( '\'' ( StringLiteral_1 | EscapedChar )* '\'' )
        ;

EscapedChar
    : '\\' ( '\\' | '\'' | '"' | ( 'B' | 'b' ) | ( 'F' | 'f' ) | ( 'N' | 'n' ) | ( 'R' | 'r' ) | ( 'T' | 't' ) | ( ( 'X' | 'x' ) ( HexDigit HexDigit ) ) | ( ( 'U' | 'u' ) ( HexDigit HexDigit HexDigit HexDigit ) ) | ( ( 'U' | 'u' ) ( HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit HexDigit ) ) ) ;

oC_NumberLiteral
    : oC_DoubleLiteral
        | oC_IntegerLiteral
        ;

oC_Parameter
    : '$' ( oC_SymbolicName | DecimalInteger ) ;

oC_PropertyExpression
    : oC_Atom SP? oC_PropertyLookup ;

oC_PropertyKeyName
    : oC_SchemaName ;

oC_IntegerLiteral
    : DecimalInteger ;

DecimalInteger
    : ZeroDigit
        | ( NonZeroDigit ( Digit )* )
        ;

HexLetter
    : ( 'A' | 'a' )
        | ( 'B' | 'b' )
        | ( 'C' | 'c' )
        | ( 'D' | 'd' )
        | ( 'E' | 'e' )
        | ( 'F' | 'f' )
        ;

HexDigit
    : Digit
        | HexLetter
        ;

Digit
    : ZeroDigit
        | NonZeroDigit
        ;

NonZeroDigit
    : NonZeroOctDigit
        | '8'
        | '9'
        ;

NonZeroOctDigit
    : '1'
        | '2'
        | '3'
        | '4'
        | '5'
        | '6'
        | '7'
        ;

ZeroDigit
    : '0' ;

oC_DoubleLiteral
    : ExponentDecimalReal
        | RegularDecimalReal
        ;

ExponentDecimalReal
    : ( ( Digit )+ | ( ( Digit )+ '.' ( Digit )+ ) | ( '.' ( Digit )+ ) ) ( 'E' | 'e' ) '-'? ( Digit )+ ;

RegularDecimalReal
    : ( Digit )* '.' ( Digit )+ ;

oC_SchemaName
    : oC_SymbolicName ;

oC_SymbolicName
    : UnescapedSymbolicName
        | EscapedSymbolicName {if ($EscapedSymbolicName.text == "``") { notifyEmptyToken($EscapedSymbolicName); }}
        | HexLetter
        | kU_NonReservedKeywords
        ;

// example of BEGIN and END: TCKWith2.Scenario1
kU_NonReservedKeywords
    : COMMENT
        | ADD
        | ALTER
        | AS
        | ATTACH
        | BEGIN
        | BY
        | CALL
        | CHECKPOINT
        | COMMENT
        | COMMIT
        | CONTAINS
        | COPY
        | COUNT
        | CYCLE
        | DATABASE
        | DECIMAL
        | DELETE
        | DETACH
        | DROP
        | EXPLAIN
        | EXPORT
        | EXTENSION
        | FORCE
        | GRAPH
        | IF
        | IS
        | IMPORT
        | INCREMENT
        | KEY
        | LOAD
        | LOGICAL
        | MATCH
        | MAXVALUE
        | MERGE
        | MINVALUE
        | NO
        | NODE
        | PROJECT
        | READ
        | REL
        | RENAME
        | RETURN
        | ROLLBACK
        | ROLE
        | SEQUENCE
        | SET
        | START
        | STRUCT
        | L_SKIP
        | LIMIT
        | TRANSACTION
        | TYPE
        | USE
        | UNINSTALL
        | UPDATE
        | WRITE
        | FROM
        | TO
        | YIELD
        | USER
        | PASSWORD
        | MAP
        ;

UnescapedSymbolicName
    : IdentifierStart ( IdentifierPart )* ;

IdentifierStart
    : ID_Start
        | Pc
        ;

IdentifierPart
    : ID_Continue
        | Sc
        ;

EscapedSymbolicName
    : ( '`' ( EscapedSymbolicName_0 )* '`' )+ ;

SP
  : ( WHITESPACE )+ ;

WHITESPACE
    : SPACE
        | TAB
        | LF
        | VT
        | FF
        | CR
        | FS
        | GS
        | RS
        | US
        | '\u1680'
        | '\u180e'
        | '\u2000'
        | '\u2001'
        | '\u2002'
        | '\u2003'
        | '\u2004'
        | '\u2005'
        | '\u2006'
        | '\u2008'
        | '\u2009'
        | '\u200a'
        | '\u2028'
        | '\u2029'
        | '\u205f'
        | '\u3000'
        | '\u00a0'
        | '\u2007'
        | '\u202f'
        | CypherComment
        ;

CypherComment
    : ( '/*' ( Comment_1 | ( '*' Comment_2 ) )* '*/' )
        | ( '//' ( Comment_3 )* CR? ( LF | EOF ) )
        ;

oC_LeftArrowHead
    : '<'
        | '\u27e8'
        | '\u3008'
        | '\ufe64'
        | '\uff1c'
        ;

oC_RightArrowHead
    : '>'
        | '\u27e9'
        | '\u3009'
        | '\ufe65'
        | '\uff1e'
        ;

oC_Dash
    : '-'
        | '\u00ad'
        | '\u2010'
        | '\u2011'
        | '\u2012'
        | '\u2013'
        | '\u2014'
        | '\u2015'
        | '\u2212'
        | '\ufe58'
        | '\ufe63'
        | '\uff0d'
        ;

fragment FF : [\f] ;

fragment EscapedSymbolicName_0 : ~[`] ;

fragment RS : [\u001E] ;

fragment ID_Continue : [\p{ID_Continue}] ;

fragment Comment_1 : ~[*] ;

fragment StringLiteral_1 : ~['\\] ;

fragment Comment_3 : ~[\n\r] ;

fragment Comment_2 : ~[/] ;

fragment GS : [\u001D] ;

fragment FS : [\u001C] ;

fragment CR : [\r] ;

fragment Sc : [\p{Sc}] ;

fragment SPACE : [ ] ;

fragment Pc : [\p{Pc}] ;

fragment TAB : [\t] ;

fragment StringLiteral_0 : ~["\\] ;

fragment LF : [\n] ;

fragment VT : [\u000B] ;

fragment US : [\u001F] ;

fragment ID_Start : [\p{ID_Start}] ;

// This is used to capture unknown lexer input (e.g. !) to avoid parser exception.
Unknown : .;
