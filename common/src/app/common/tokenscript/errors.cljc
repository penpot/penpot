;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.errors
  "Structured errors for the tokenscript interpreter.

  Every error raised by the lexer, parser, interpreter or processor is
  an `ex-info` whose data is an error map:

    {:type  :tokenscript/error
     :kind  :lexer | :parser | :interpreter | :processor
     :code  :lexer/invalid-character
     :hint  \"Invalid character ...\"       ; message without position
     :message \"Line 1: Invalid ...\"       ; message with position info
     :data  {...}                            ; code specific data
     :line  1
     :token {...}}

  The processor stores the same maps as values in its issues map, so
  errors are plain data on both sides of the API. The code keyword
  namespace selects the kind; `code->compliance-code` maps a code to the
  string identifier used by the cross-runtime compliance suite."
  (:require
   [clojure.string :as str]))

;; Codes ----------------------------------------------------------------------

(def ^:private kind-prefixes
  {"lexer" "LEXER"
   "parser" "PARSER"
   "interpreter" "INT"
   "operations" "OP"
   "symbols" "SYM"
   "functions" "FN"
   "config" "CFG"
   "processor" "PROC"
   "color" "COLOR"
   "unit" "UNIT"})

(defn code->kind
  "Maps an error code to the error kind (the TS error class)."
  [code]
  (case (namespace code)
    "lexer" :lexer
    "parser" :parser
    "processor" :processor
    :interpreter))

(defn code->compliance-code
  "Maps an error code keyword to the cross-runtime string identifier,
  e.g. `:interpreter/unknown-reference` -> \"INT_UNKNOWN_REFERENCE\"."
  [code]
  (str (get kind-prefixes (namespace code))
       "_"
       (str/upper-case (str/replace (name code) "-" "_"))))

;; Messages -------------------------------------------------------------------

(defn- join-list
  [v]
  (if (sequential? v) (str/join ", " v) (str v)))

(def messages
  {;; Lexer
   :lexer/invalid-character
   (fn [{:keys [char position description]}]
     (str "Invalid character '" char "' at position " position "." (when description (str " " description))))
   :lexer/unterminated-string
   (fn [{:keys [quote-type]}] (str "Unterminated string, missing '" quote-type "'."))
   :lexer/unterminated-reference "Unterminated reference, missing '}'."
   :lexer/empty-variable-name "Empty variable name."
   :lexer/invalid-hex-color-format
   (fn [{:keys [value expected-length]}]
     (str "Invalid hex color format: " value ". Length should be " expected-length "."))
   :lexer/expected-character
   (fn [{:keys [expected got]}] (str "Expected character " expected " got " got))
   :lexer/multiple-decimal-points
   (fn [{:keys [value]}] (str "Invalid number '" value "': multiple decimal points."))
   :lexer/unterminated-template-string "Unterminated template string, missing '`'."

   ;; Parser
   :parser/unexpected-token (fn [{:keys [token]}] (str "Unexpected token: " token))
   :parser/expected-token-type
   (fn [{:keys [expected got]}] (str "Expected token type " expected " but got " got))
   :parser/condition-must-be-boolean "If/elif condition must be a boolean"
   :parser/invalid-syntax (fn [{:keys [message]}] (if message (str message) "Invalid syntax"))
   :parser/unexpected-end "Unexpected end of input"
   :parser/tolerant-requires-inline
   "Tolerant mode only supports inline expressions. Use parse(true) for tolerant parsing."
   :parser/unallowed-inline-syntax
   (fn [{:keys [original-error]}] (str "Syntax not allowed in inline mode: " original-error))

   ;; Interpreter
   :interpreter/unknown-node-type
   (fn [{:keys [node-type]}] (str "No visit method for AST node type: " node-type))
   :interpreter/arithmetic-requires-number
   (fn [{:keys [operator left-type right-type]}]
     (str "Arithmetic operator " operator " requires Number or NumberWithUnit operands, got "
          left-type " and " right-type "."))
   :interpreter/unknown-binary-operator
   (fn [{:keys [operator token-type]}] (str "Unknown binary operator: " operator " or type " token-type))
   :interpreter/unary-minus-null
   (fn [{:keys [symbol-type]}] (str "Cannot apply unary '-' to a null " symbol-type "."))
   :interpreter/unary-plus-non-number
   (fn [{:keys [value]}] (str "Cannot apply unary '+' to non-number value: " value))
   :interpreter/unary-not-non-boolean
   (fn [{:keys [value]}] (str "Cannot apply NOT to non-boolean value: " value "."))
   :interpreter/unknown-unary-operator
   (fn [{:keys [operator]}] (str "Unknown unary operator type: " operator))
   :interpreter/unknown-reference (fn [{:keys [reference]}] (str "Unknown reference: " reference))
   :interpreter/unknown-function (fn [{:keys [function-name]}] (str "Unknown function: '" function-name "'"))
   :interpreter/invalid-variable-name
   (fn [{:keys [name]}]
     (str "Invalid variable name '" name "'. Use a simple name (and underscores) without '.', '-', '['."))
   :interpreter/variable-already-defined
   (fn [{:keys [name]}] (str "Variable '" name "' already defined. Use a different name."))
   :interpreter/invalid-variable-type
   (fn [{:keys [type-name valid-types]}]
     (str "Invalid variable type '" type-name "'. Use a valid type. (" valid-types ")"))
   :interpreter/invalid-value-for-variable
   (fn [{:keys [value type name]}]
     (str "Invalid value '" value "' ('" type "') for variable '" name "'. Use a valid value."))
   :interpreter/variable-not-found (fn [{:keys [name]}] (str "Variable '" name "' not found."))
   :interpreter/assignment-value-null
   (fn [{:keys [identifier]}] (str "Assignment value for '" identifier "' is null."))
   :interpreter/type-mismatch
   (fn [{:keys [value found-type expected-type identifier]}]
     (str "Invalid value '" value "' (Found '" found-type "', expected '" expected-type
          "') for variable '" identifier "'. Use a valid value."))
   :interpreter/cannot-assign-type
   (fn [{:keys [value-type identifier variable-type]}]
     (str "Cannot assign " value-type " to variable '" identifier "' of type " variable-type "."))
   :interpreter/method-returned-null
   (fn [{:keys [method-name]}] (str "Method '" method-name "' returned null or undefined"))
   :interpreter/method-not-found
   (fn [{:keys [method-name value type]}]
     (str "Method '" method-name "' not found on '" value "' (" type ")"))
   :interpreter/attribute-returned-null
   (fn [{:keys [attribute-name]}] (str "Attribute '" attribute-name "' returned null or undefined"))
   :interpreter/attribute-not-found
   (fn [{:keys [attribute-name value type]}]
     (str "Attribute '" attribute-name "' not found on '" value "' (" type ")"))
   :interpreter/cannot-access-attributes
   (fn [{:keys [type]}] (str "Cannot access attributes on " type))
   :interpreter/max-iterations-exceeded "Max iterations exceeded in while loop."
   :interpreter/while-condition-not-boolean "While loop condition must be a boolean."
   :interpreter/if-condition-not-boolean "If/elif condition must be a boolean."
   :interpreter/for-each-not-list
   (fn [{:keys [actual-type]}] (str "for...in requires a List, got " actual-type "."))
   :interpreter/for-each-variable-shadow
   (fn [{:keys [name]}] (str "for...in variable '" name "' shadows an existing variable in this scope."))
   :interpreter/for-each-duplicate-vars
   (fn [{:keys [name]}]
     (str "for...in item and index variables must have different names, both are '" name "'."))
   :interpreter/template-invalid-type
   (fn [{:keys [value-type]}]
     (str "Cannot interpolate " value-type " in template string. Only primitive values are allowed."))
   :interpreter/unknown-error "An unknown error occurred during interpretation."

   ;; Operations
   :operations/unsupported-operand-type
   (fn [{:keys [type]}] (str "Unsupported operand type for unit decomposition: " type))
   :operations/cannot-mix-units (fn [{:keys [units]}] (str "Cannot mix units: " (join-list units)))
   :operations/incompatible-types
   (fn [{:keys [type-a type-b]}] (str "Cannot compare " type-a " with " type-b ". Incompatible types."))
   :operations/cannot-compare-units
   (fn [{:keys [unit-a unit-b]}]
     (str "Cannot compare NumberWithUnit of different units: " unit-a " and " unit-b))
   :operations/ordering-with-null "Cannot perform ordering comparison with null values."
   :operations/logical-requires-boolean
   (fn [{:keys [operator]}] (str operator " operator requires boolean operands."))
   :operations/division-by-zero "Division by zero."
   :operations/unit-exponent-not-supported
   (fn [{:keys [unit-a unit-b]}]
     (str "Cannot raise " unit-a " to the power of " unit-b ". Unit exponents are not supported."))

   ;; Symbols
   :symbols/value-must-be-type
   (fn [{:keys [expected-type actual-type]}] (str "Value must be " expected-type ", got " actual-type "."))
   :symbols/value-is-null (fn [{:keys [expected-type]}] (str "Value must be " expected-type ", got null."))
   :symbols/method-not-found
   (fn [{:keys [method-name type]}]
     (str "Method '" method-name "' not found or invalid arguments on type '" type "'."))
   :symbols/missing-required-argument
   (fn [{:keys [argument-name method-name]}]
     (str "Missing required argument '" argument-name "' for method '" method-name "'."))
   :symbols/attribute-not-found
   (fn [{:keys [attribute-name type]}] (str "Attribute '" attribute-name "' not found on " type "."))
   :symbols/cannot-set-attribute
   (fn [{:keys [attribute-name type]}] (str "Cannot set attribute '" attribute-name "' on type '" type "'."))
   :symbols/invalid-radix (fn [{:keys [radix]}] (str "Invalid radix: " radix ". Must be between 2 and 36."))
   :symbols/radix-conversion-error
   (fn [{:keys [base error]}] (str "Error converting to base " base ": " error "."))
   :symbols/cannot-concatenate (fn [{:keys [type]}] (str "Cannot concatenate " type " to String."))
   :symbols/cannot-split (fn [{:keys [type]}] (str "Cannot split String by " type "."))
   :symbols/index-out-of-range (fn [{:keys [operation]}] (str "Index out of range for " operation "."))
   :symbols/key-must-be-string
   (fn [{:keys [type]}] (str "Key must be a StringSymbol or string, got " type "."))
   :symbols/cannot-operation-on-token-type
   (fn [{:keys [operation value-type]}] (str "Cannot " operation " Token with " value-type " value."))
   :symbols/invalid-hex-color (fn [{:keys [value]}] (str "Invalid hex color format: " value))
   :symbols/color-manager-not-available "ColorManager not available"
   :symbols/invalid-color-value
   (fn [{:keys [value type]}]
     (str "Value " value " must be string, attributes record, or null, got " type "."))

   ;; Functions
   :functions/expects-number-arguments
   (fn [{:keys [function-name]}] (str function-name "() expects number arguments."))
   :functions/requires-min-arguments
   (fn [{:keys [function-name min-args]}]
     (str function-name "() requires at least " min-args " argument" (if (= min-args 1) "" "s") "."))
   :functions/expects-type-argument
   (fn [{:keys [function-name expected-type argument-position]}]
     (str function-name "() expects " expected-type " as " argument-position " argument."))
   :functions/division-by-zero (fn [{:keys [function-name]}] (str function-name "() division by zero."))
   :functions/argument-out-of-range
   (fn [{:keys [function-name constraint]}] (str function-name "() argument must be " constraint "."))
   :functions/invalid-base
   (fn [{:keys [function-name constraint]}] (str function-name "() base must be " constraint "."))
   :functions/parse-error
   (fn [{:keys [function-name value base]}]
     (str "Invalid string for " function-name ": \"" value "\" with base " base "."))
   :functions/unit-conversion-failed
   (fn [{:keys [function-name error]}]
     (str (or function-name "function") "() unit conversion failed" (when error (str ": " error))))
   :functions/no-config-available
   (fn [{:keys [function-name]}] (str "No config available for dynamic function '" (or function-name "unknown") "'"))
   :functions/function-returned-null
   (fn [{:keys [function-name]}] (str "Dynamic function '" (or function-name "unknown") "' returned null"))
   :functions/execution-error
   (fn [{:keys [function-name error]}]
     (str "Error executing dynamic function '" (or function-name "unknown") "'" (when error (str ": " error))))

   ;; Config
   :config/no-spec-found (fn [{:keys [spec-name]}] (str "No spec found for " spec-name))
   :config/no-type-found (fn [{:keys [type-name]}] (str "No type found for " type-name))
   :config/invalid-specification
   (fn [{:keys [kind uri error]}] (str "Invalid " kind " specification for " uri ": " error))

   ;; Processor
   :processor/token-not-found (fn [{:keys [token-name]}] (str "Token '" token-name "' not found"))
   :processor/token-already-exists (fn [{:keys [token-name]}] (str "Token '" token-name "' already exists."))
   :processor/circular-dependency
   (fn [{:keys [tokens]}] (str "Circular dependency detected: " (join-list tokens)))
   :processor/sub-field-not-resolved (fn [{:keys [field-path]}] (str "Sub-field '" field-path "' not resolved"))
   :processor/dependency-error
   (fn [{:keys [token-name chain cause]}]
     (str "Token '" token-name "' failed due to dependency error: " (str/join " → " chain)
          "\nRoot cause: " (:message cause)))
   :processor/no-themes-found (fn [{:keys [theme-name]}] (str "No themes found for theme \"" theme-name "\""))
   :processor/theme-not-found
   (fn [{:keys [theme-name available-themes]}]
     (str "Theme \"" theme-name "\" not found. Available: " (join-list available-themes)))
   :processor/token-set-not-found (fn [{:keys [set-name]}] (str "Token set \"" set-name "\" not found"))
   :processor/token-set-invalid (fn [{:keys [set-name]}] (str "Token set \"" set-name "\" is not an object"))
   :processor/no-sets-to-process "No sets to process"
   :processor/multiple-sets-no-selection
   (fn [{:keys [set-names]}]
     (str "Multiple sets found (" (join-list set-names) ") - specify activeSets or activeTheme"))
   :processor/unknown-parsing-error
   (fn [{:keys [error]}] (str "Unknown parsing error" (when error (str ": " error))))
   :processor/resolver-not-initialized "Resolver not initialized. Call build() first."

   ;; Color
   :color/initializer-crashed "Initializer crashed!"
   :color/initializer-construct-failed
   (fn [{:keys [error]}] (str "Could not construct initializer from schema" (when error (str ": " error))))
   :color/initializer-not-found (fn [{:keys [keyword]}] (str "No initializer found for keyword '" keyword "'"))
   :color/conversion-target-not-found
   (fn [{:keys [target-uri]}] (str "Conversion function crashed! No target spec found for " target-uri))
   :color/source-uri-not-found
   (fn [{:keys [color-type]}] (str "No source URI found for color type '" color-type "'"))
   :color/target-uri-not-found
   (fn [{:keys [color-type]}] (str "No target URI found for color type '" color-type "'"))
   :color/conversion-error (fn [{:keys [message]}] (if message (str message) "Color conversion failed"))
   :color/string-value-assignment
   (fn [{:keys [attributes identifier color-type]}]
     (str "Cannot set attributes '" attributes "' for variable " identifier " on Color type " color-type "."))
   :color/attribute-chain-too-long
   (fn [{:keys [attributes identifier color-type]}]
     (str "Attributes chain '" attributes "' for variable " identifier " on Color type " color-type
          " may not exceed one element."))
   :color/missing-spec
   (fn [{:keys [identifier color-type]}]
     (str "No spec " color-type " defined for variable " identifier " on Color type " color-type "."))
   :color/missing-schema (fn [{:keys [color-type]}] (str "No schema defined for Color type " color-type "."))
   :color/missing-attribute-schema
   (fn [{:keys [attribute identifier color-type]}]
     (str "No schema found for key " attribute " for variable " identifier " on Color type " color-type "."))
   :color/invalid-attribute-type
   (fn [{:keys [attribute-type valid-types]}]
     (str "Invalid attribute type '" attribute-type "'. Use a valid type. (" valid-types ")"))
   :color/invalid-alpha-value
   (fn [{:keys [alpha]}] (str "Invalid alpha value '" alpha "'. Alpha must be between 0 and 1."))

   ;; Unit
   :unit/conversion-crashed (fn [{:keys [error]}] (str "Conversion function crashed!" (when error (str " " error))))
   :unit/source-uri-not-found (fn [{:keys [unit]}] (str "No source URI found for unit '" unit "'"))
   :unit/relative-requires-two-numbers "Relative conversion requires exactly 2 numbers"
   :unit/no-to-absolute-script
   (fn [{:keys [unit]}] (str "No to_absolute script found for relative unit '" unit "'"))
   :unit/to-absolute-must-return-number "to_absolute script must return a number"
   :unit/cannot-convert-multiple-relative "Cannot convert multiple relative units to a common format."
   :unit/no-valid-conversion-path "No valid conversion paths found for the provided inputs."})

(defn message
  "Renders the human readable message for `code` and `data`."
  [code data]
  (let [template (get messages code)]
    (cond
      (nil? template) (str "Unknown error: " code)
      (fn? template) (template (or data {}))
      :else template)))

(defn valid-code?
  [code]
  (contains? messages code))

;; Construction ---------------------------------------------------------------

(defn- format-message
  [hint line token]
  (let [base (if (some? line) (str "Line " line ": " hint) hint)
        tv (:value token)]
    (if (and (some? tv) (not= tv ""))
      (str base "\nNear token: " tv)
      base)))

(defn error-map
  "Builds the error data map for `code`. Options:

    :data  code specific data
    :token the source token near the error
    :line  explicit line (defaults to the token line)"
  ([code] (error-map code nil))
  ([code {:keys [data token line]}]
   (let [data (or data {})
         line (or line (:line token))
         hint (message code data)]
     (cond-> {:type :tokenscript/error
              :kind (code->kind code)
              :code code
              :hint hint
              :message (format-message hint line token)
              :data data}
       (some? line) (assoc :line line)
       (some? token) (assoc :token token)))))

(defn error
  "Creates (without throwing) the ex-info for `code`."
  ([code] (error code nil))
  ([code opts]
   (let [m (error-map code opts)]
     (ex-info (:message m) m (:cause opts)))))

(defn raise
  ([code] (throw (error code nil)))
  ([code opts] (throw (error code opts))))

;; Inspection -----------------------------------------------------------------

(defn error-map?
  "True for error data maps (the `ex-data` of raised errors and the
  values stored in processor issue maps)."
  [v]
  (and (map? v) (= :tokenscript/error (:type v))))

(defn language-error?
  "True when `e` is an exception raised by this module."
  [e]
  (error-map? (ex-data e)))

(defn ->error-map
  "Returns the error map for either an exception or an error map."
  [e]
  (cond
    (error-map? e) e
    (language-error? e) (ex-data e)
    :else nil))

(defn describe
  "Short description of any throwable or error map, for wrapping foreign
  errors into error data."
  [e]
  (cond
    (nil? e) nil
    (string? e) e
    (error-map? e) (:message e)
    (language-error? e) (ex-message e)
    #?(:clj (instance? Throwable e) :cljs (instance? js/Error e)) (ex-message e)
    :else (str e)))
