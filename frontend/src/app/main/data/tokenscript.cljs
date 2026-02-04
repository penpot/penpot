(ns app.main.data.tokenscript
  (:require
   ["@penpot/tokenscript" :refer [BaseSymbolType
                                  ColorSymbol
                                  ListSymbol NumberSymbol
                                  NumberWithUnitSymbol
                                  ProcessorError
                                  processTokens
                                  TokenSymbol
                                  makeConfig]]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.main.data.workspace.tokens.errors :as wte]))

(l/set-level! :debug)

;; Config ----------------------------------------------------------------------

(def config (makeConfig))

;; Class predicates ------------------------------------------------------------
;; Predicates to get information about the tokenscript interpreter symbol type
;; Or to determine the error

(defn tokenscript-symbol? [v]
  (instance? BaseSymbolType v))

(defn structured-token? [v]
  (instance? TokenSymbol v))

(defn structured-record-token? [^js v]
  (and (structured-token? v) (instance? js/Map (.-value v))))

(defn structured-array-token? [^js v]
  (and (structured-token? v) (instance? js/Array (.-value v))))

(defn number-with-unit-symbol? [v]
  (instance? NumberWithUnitSymbol v))

(defn number-symbol? [v]
  (instance? NumberSymbol v))

(defn list-symbol? [v]
  (instance? ListSymbol v))

(defn color-symbol? [v]
  (instance? ColorSymbol v))

(defn processor-error? [err]
  (instance? ProcessorError err))

;; Conversion Tools ------------------------------------------------------------
;; Helpers to convert tokenscript symbols to penpot accepted formats

(defn color-symbol->hex-string [^js v]
  (when (color-symbol? v)
    (.toString (.to v "hex"))))

(defn color-alpha [^js v]
  (if (.isHex v)
    1
    (or (.getAttribute v "alpha") 1)))

(defn color-symbol->penpot-color [^js v]
  {:color (color-symbol->hex-string v)
   :opacity (color-alpha v)})

(defn rem-number-with-unit? [v]
  (and (number-with-unit-symbol? v)
       (= (.-unit v) "rem")))

(defn rem->px [^js v]
  (* (.-value v) 16))

(declare tokenscript-symbols->penpot-unit)

(defn structured-token->penpot-map
  "Converts structured token (record or array) to penpot map format.
  Structured tokens are non-primitive token types like `typography` or `box-shadow`."
  [^js token-symbol]
  (if (instance? js/Array (.-value token-symbol))
    (mapv structured-token->penpot-map (.-value token-symbol))
    (let [entries (es6-iterator-seq (.entries (.-value token-symbol)))]
      (into {} (map (fn [[k v :as V]]
                      [(keyword k) (tokenscript-symbols->penpot-unit v)])
                    entries)))))

(defn tokenscript-symbols->penpot-unit [^js v]
  (cond
    (structured-token? v) (structured-token->penpot-map v)
    (list-symbol? v) (tokenscript-symbols->penpot-unit (.nth 1 v))
    (color-symbol? v) (.-value (.to v "hex"))
    (rem-number-with-unit? v) (rem->px v)
    :else (.-value v)))

;; Processors ------------------------------------------------------------------
;; The processor resolves tokens
;; resolved/error tokens get put back into a clojure structure directly during build time
;; For updating tokens we use the `TokenResolver` crud methods from the processing result
;; The `TokenResolver` has detailed information for each tokens dependency graph

(defn create-token-builder
  "Collects resolved tokens during build time into a clojure structure.
   Returns Tokenscript Symbols in `:resolved-value` key."
  [tokens]
  (let [output (volatile! tokens)

        ;; When a token is resolved (No parsing / reference errors) we assing `:resolved-value` for the original token
        on-resolve
        (fn [^js/String token-name ^js/Symbol resolved-symbol]
          (vswap! output assoc-in [token-name :resolved-value] resolved-symbol))

        ;; When a token contains any errors we assing `:errors` for the original token
        on-error
        (fn [^js/String token-name ^js/Error _error ^js/String _original-value]
          (let [value (get tokens token-name)
                default-error [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]]
            (vswap! output assoc-in [token-name :errors] default-error)))

        ;; Extract the atom value
        get-result
        (fn [] @output)]
    #js {:onResolve on-resolve
         :onError on-error
         :getResult get-result}))

(defn clj->token->tokenscript-token
  "Convert penpot token into a format that tokenscript can handle."
  [{:keys [type value]}]
  #js {"$type" (name type)
       "$value" (clj->js value)})

(defn clj-tokens->tokenscript-tokens
  "Convert penpot map of tokens into tokenscript map structure.
  tokenscript accepts a map of [token-name {\"$type\": string, \"$value\": any}]"
  [tokens]
  (let [token-map (js/Map.)]
    (doseq [[k token] tokens]
      (.set token-map k (clj->token->tokenscript-token token)))
    token-map))

(defn process-tokens
  "Builds tokens using `tokenscript`."
  [tokens]
  (let [input (clj-tokens->tokenscript-tokens tokens)
        result (processTokens input #js {:config config
                                         :builder (create-token-builder tokens)})]
    result))

(defn update-token
  [tokens token]
  (let [result (process-tokens tokens)
        resolver (.-resolver result)]
    (.updateToken resolver #js {:tokenPath (:name token)
                                :tokenData (clj->token->tokenscript-token token)})))

;; Main ------------------------------------------------------------------------

(defn resolve-tokens [tokens]
  (let [tpoint (ct/tpoint-ms)
        result (process-tokens tokens)
        elapsed (tpoint)]
    (l/dbg :hint "tokenscript/resolve-tokens" :elapsed elapsed)
    (.-output result)))
