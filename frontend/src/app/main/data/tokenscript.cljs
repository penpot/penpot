;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.tokenscript
  "Resolves the workspace tokens with the tokenscript interpreter
  (`tokenscript.core`, from penpot/tokenscript-interpreter).

  `resolve-tokens` takes the usual map of token name to token and returns
  the same map with `:resolved-value` (a value ready for shape attributes:
  numbers in pixels, colors as hex strings, composites as maps),
  `:resolved-symbol` (the tokenscript value, see
  `tokenscript.symbols`) and `:errors` (Penpot error maps, see
  `app.main.data.workspace.tokens.errors`) added to each token.
  `resolve-sets` takes ordered token sets instead of a merged map.

  Resolution is incremental: the interpreter state of the last calls is
  kept, and a call re-resolves and converts only the tokens that changed
  since the previous one and the tokens depending on them."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.types.token :as cto]
   [app.main.data.workspace.tokens.errors :as wte]
   [cuerdas.core :as str]
   [tokenscript.core :as tsc]
   [tokenscript.errors :as tse]
   [tokenscript.processor :as processor]
   [tokenscript.symbols :as sym]))

;; Value conversion -----------------------------------------------------------

(defn- color->hex-string
  "Hex string of a color symbol (`#rrggbbaa` when it has alpha)."
  [color]
  (sym/to-string (tsc/color->hex color)))

(defn- symbol->value
  "Converts a resolved tokenscript value into the shape used by shape
  attributes and the token forms:

    - colors: hex strings (alpha as `#rrggbbaa`)
    - `rem` values: pixels (16px root font size), `%` values: fractions
    - other numbers with unit: the number
    - structured tokens (typography, shadows): maps with keyword keys and
      vectors
    - null: nil"
  [v]
  (let [entries->map (fn [entries]
                       (into {} (map (fn [[k v]] [(keyword k) (symbol->value v)])) entries))]
    (cond
      (nil? v) nil
      (sym/null? v) nil
      (sym/token? v) (let [content (sym/value v)]
                       (if (map? content)
                         (entries->map content)
                         (mapv symbol->value content)))
      (sym/list? v) (mapv symbol->value (sym/value v))
      (sym/dictionary? v) (entries->map (sym/value v))
      (sym/color? v) (color->hex-string v)
      (sym/number-with-unit? v) (let [n (sym/value v)]
                                  (case (:unit v)
                                    "rem" (* n 16)
                                    "%" (/ n 100)
                                    n))
      (sym/symbol? v) (sym/value v)
      :else v)))

;; Value validation -----------------------------------------------------------
;;
;; Tokenscript resolves any expression; these are the Penpot level checks
;; on the resolved value of each token type (ranges, allowed keywords,
;; composite shapes) that decide whether a token can be applied.

(defn- words->string
  "Values like `700 Italic` resolve to a list of words; font weights are
  strings."
  [v]
  (if (sequential? v) (str/join " " (map str v)) v))

(defn- font-family->vector
  "Font families are always a flat vector of family names, also when the
  value is a single name or references a font family token."
  [v]
  (cond
    (string? v) [v]
    (sequential? v) (vec (flatten v))
    :else v))

(defn- line-height->ratio
  "Line heights in pixels are stored relative to the font size. Returns
  the ratio or an error when the font size is not available."
  [line-height font-size]
  (if (and (sym/number-with-unit? line-height) (= "px" (:unit line-height)))
    (if (number? font-size)
      {:value (/ (sym/value line-height) font-size)}
      {:error (wte/error-with-value :error.style-dictionary/composite-line-height-needs-font-size font-size)})
    {:value (symbol->value line-height)}))

(defn- typography->value
  "Converts a resolved typography composite, adjusting the fields the
  text attributes expect. With `line-height-ratio` a line height in
  pixels is converted relative to the font size."
  [s line-height-ratio]
  (let [value (symbol->value s)]
    (if-not (map? value)
      {:value value}
      (let [value       (cond-> value
                          (contains? value :font-family) (update :font-family font-family->vector)
                          (contains? value :font-weight) (update :font-weight words->string))
            line-height (when (and line-height-ratio (sym/token? s))
                          (get (sym/value s) "line-height"))]
        (if (some? line-height)
          (let [{ratio :value error :error} (line-height->ratio line-height (:font-size value))]
            (if error
              {:error error}
              {:value (assoc value :line-height ratio)}))
          {:value value})))))

(defn- convert-value
  "Converts the resolved symbol of a token to the value shape expected by
  its type. Returns `{:value v}` or `{:error e}`. Options:

    :line-height-ratio convert typography line heights in pixels to a
                       ratio of the font size (default true)"
  ([type s] (convert-value type s nil))
  ([type s {:keys [line-height-ratio] :or {line-height-ratio true}}]
   (case type
     :typography  (typography->value s line-height-ratio)
     :font-family {:value (font-family->vector (symbol->value s))}
     :font-weight {:value (words->string (symbol->value s))}
     {:value (symbol->value s)})))

(defn- convert-token-value
  "`convert-value` for a token: a failing conversion (e.g. a color that
  cannot be written as hex) is an error of that token, not of the whole
  resolution."
  [{:keys [type value]} s]
  (try
    (convert-value type s)
    (catch :default _
      {:error (wte/error-with-value :error.style-dictionary/invalid-token-value value)})))

(defn plugin-value
  "Resolved value of a token as exposed by the plugin API: like
  `:resolved-value` but with typography line heights left as resolved."
  [{:keys [type resolved-symbol]}]
  (when (some? resolved-symbol)
    (:value (convert-value type resolved-symbol {:line-height-ratio false}))))

(defn- validate-value
  "Returns a Penpot error for the resolved `value` of `token`, or nil when
  it is acceptable. The interpreter validates the kind of value of each
  type; these are the Penpot specific limits on top."
  [{:keys [type]} value]
  (if (and (number? value) (cto/number-out-of-bounds? value))
    (wte/error-with-value :error.token/number-too-large value)
    (case type
      :text-decoration
      (when-not (and (string? value) (cto/valid-text-decoration value))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-text-decoration value))

      :font-weight
      (when-not (cto/valid-font-weight-variant (str value))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-font-weight value))

      :font-family
      (when-not (or (string? value) (and (sequential? value) (every? string? value)))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-font-family value))

      nil)))

;; Errors ---------------------------------------------------------------------

(defn- missing-reference-of
  "Missing token name behind an issue, if the root cause is a missing
  token."
  [{:keys [code data]}]
  (case code
    :processor/token-not-found (:token-name data)
    :interpreter/unknown-reference (:reference data)
    :processor/dependency-error (let [cause (:cause data)]
                                  (when (= :processor/token-not-found (:code cause))
                                    (get-in cause [:data :token-name])))
    nil))

(defn- circular?
  [{:keys [code data]}]
  (or (= :processor/circular-dependency code)
      (= :processor/circular-dependency (get-in data [:cause :code]))))

(defn- issue->error
  "Maps a tokenscript issue to a Penpot token error. The interpreter only
  reports codes and data; the messages are Penpot's. Codes without a
  message of their own are an invalid value."
  [{:keys [code data] :as issue} value]
  (cond
    (circular? issue)
    (wte/error-with-value :error.token/circular-reference nil)

    (missing-reference-of issue)
    (wte/error-with-value :error.style-dictionary/missing-reference [(missing-reference-of issue)])

    :else
    (case code
      (:operations/division-by-zero :functions/division-by-zero)
      (wte/error-with-value :error.token/division-by-zero nil)

      :operations/cannot-mix-units
      (wte/error-with-value :error.token/incompatible-units (:units data))

      :operations/cannot-compare-units
      (wte/error-with-value :error.token/incompatible-units [(:unit-a data) (:unit-b data)])

      :interpreter/unknown-function
      (wte/error-with-value :error.token/unknown-function (:function-name data))

      (:functions/argument-out-of-range :functions/invalid-base)
      (wte/error-with-value :error.token/function-argument-out-of-range (:function-name data))

      (wte/error-with-value :error.style-dictionary/invalid-token-value value))))

(defn- validation-error
  "Maps a failed token type validation of the interpreter (an issue with
  an `INVALID_<TYPE>_<REASON>` code) to the Penpot error of the token's
  type."
  [{:keys [code]} {:keys [type value]}]
  (case type
    :opacity (wte/error-with-value :error.style-dictionary/invalid-token-value-opacity value)
    :stroke-width (wte/error-with-value :error.style-dictionary/invalid-token-value-stroke-width value)
    :text-case (wte/error-with-value :error.style-dictionary/invalid-token-value-text-case value)
    :text-decoration (wte/error-with-value :error.style-dictionary/invalid-token-value-text-decoration value)
    :font-weight (wte/error-with-value :error.style-dictionary/invalid-token-value-font-weight value)
    :font-family (wte/error-with-value :error.style-dictionary/invalid-token-value-font-family value)
    :typography (wte/error-with-value :error.style-dictionary/invalid-token-value-typography value)
    :shadow (wte/error-with-value :error.style-dictionary/invalid-token-value-shadow value)
    (if (= "INVALID_NUMBER_UNIT" code)
      (wte/error-with-value :error.style-dictionary/value-with-units value)
      (wte/error-with-value :error.style-dictionary/invalid-token-value value))))

(defn- token-errors
  [issues token]
  (->> issues
       (keep (fn [issue]
               (cond
                 (tse/error-map? issue) (issue->error issue (:value token))
                 (= :tokenscript/issue (:type issue)) (validation-error issue token))))
       (seq)))

;; Resolution -----------------------------------------------------------------

(defn- quote-font-family
  "Quotes a font family name so tokenscript reads it as one string instead
  of parsing its words (`Red` would become a color, `2P` a number and a
  word). Entries with a token reference or already quoted are kept."
  [family]
  (cond
    (or (not (string? family))
        (seq (cto/find-token-value-references family))
        (re-matches #"^(['\"]).*\1$" family))
    family

    (not (str/includes? family "\""))
    (str "\"" family "\"")

    (not (str/includes? family "'"))
    (str "'" family "'")

    :else
    family))

(defn- quote-font-families
  [value]
  (if (sequential? value)
    (mapv quote-font-family value)
    value))

(defn- token->input
  "Token data for the interpreter. Types are passed by their DTCG names,
  which the interpreter's bundled token types validate. The token id goes
  along as metadata, which the interpreter keeps with the resolved name."
  [{:keys [id type value]}]
  (cond-> {:value (case type
                    :font-family (quote-font-families value)
                    :typography (cond-> value
                                  (map? value) (d/update-when :font-family quote-font-families))
                    value)}
    (some? type) (assoc :type (get cto/token-type->dtcg-token-type type (name type)))
    (some? id) (assoc :metadata id)))

(defn- empty-value?
  [token]
  (nil? (:value token)))

(defn- merge-sets
  "The tokens of `sets` as one map, later sets winning: the names of a
  resolution."
  [sets]
  (if (= 1 (count sets))
    (second (first sets))
    (reduce (fn [acc [_ tokens]] (merge acc tokens)) {} sets)))

(defn- set-inputs
  "Interpreter inputs of the `tokens` of one set, without the names in
  `empty-names`. `prev` is `{:tokens .. :inputs ..}` of the same set in
  the previous resolution: a token that is the same object as before gets
  its previous input, and with `same-empty?` an unchanged tokens map gets
  the previous inputs as they are, so the interpreter sees unchanged data
  by identity and skips it without comparing."
  [tokens empty-names {prev-tokens :tokens prev-inputs :inputs} same-empty?]
  (if (and same-empty? (identical? tokens prev-tokens))
    prev-inputs
    (persistent!
     (reduce-kv (fn [acc name token]
                  (if (contains? empty-names name)
                    acc
                    (assoc! acc name (or (when (identical? token (get prev-tokens name))
                                           (get prev-inputs name))
                                         (token->input token)))))
                (transient {})
                tokens))))

(defn- output-token
  "`token` with `:resolved-value` and `:resolved-symbol`, or `:errors`,
  from the interpreter `result`."
  [name token {:keys [tokens issues]}]
  (if (empty-value? token)
    (assoc token :errors [(wte/get-error-code :error.token/empty-input)])
    (let [errors (token-errors (get issues name) token)
          {:keys [value error]} (when-not errors (convert-token-value token (get tokens name)))
          error (or error (when-not errors (validate-value token value)))]
      (cond
        errors (assoc token :errors (vec errors))
        error (assoc token :errors [error])
        :else (assoc token
                     :resolved-value value
                     :resolved-symbol (get tokens name))))))

(defn- resolve-sets*
  "Resolves `sets` starting from `base`, the previous resolution (nil for
  the first). The interpreter keeps its state across resolutions: it
  re-resolves only the names whose data changed and the tokens that can
  observe them, and only those names are converted again. Every other
  entry of the output is the one of `base`, the same object."
  [sets base]
  (let [tpoint (ct/tpoint-ms)
        winners (merge-sets sets)
        empty-names (into #{} (keep (fn [[name token]] (when (empty-value? token) name))) winners)
        same-empty? (boolean (and base (= empty-names (:empty-names base))))
        inputs (mapv (fn [[set-id tokens]]
                       [set-id (set-inputs tokens empty-names (get-in base [:inputs set-id]) same-empty?)])
                     sets)
        result (if base
                 (processor/set-sets (get-in base [:result :state]) inputs)
                 (tsc/build inputs))
        resolved (:resolved result)
        prev-winners (:winners base)
        prev-resolved (get-in base [:result :resolved])
        prev-output (:output base)
        output (persistent!
                (reduce-kv
                 (fn [acc name token]
                   (assoc! acc name
                           (if (and (identical? token (get prev-winners name))
                                    (identical? (get resolved name) (get prev-resolved name)))
                             (get prev-output name)
                             (output-token name token result))))
                 (transient {})
                 winners))]
    (l/dbg :hint "tokenscript/resolve-sets" :elapsed (tpoint) :tokens (count winners))
    {:sets sets
     :winners winners
     :empty-names empty-names
     :inputs (into {} (map (fn [[set-id tokens] [_ set-inputs]] [set-id {:tokens tokens :inputs set-inputs}]) sets inputs))
     :result result
     :output output}))

;; Several components resolve the same sets on every change (the sidebar,
;; the forms and the propagation each compute the active tokens
;; separately), and a form resolves a new map on every keystroke. The last
;; few resolutions are kept with their interpreter state: a known input
;; returns its output, a new one is resolved from the most recent entry,
;; which re-resolves only what changed. The entries are values, so a
;; preview resolved from an entry leaves it as it is.
(def ^:private cache-size 8)

(defonce ^:private cache (volatile! []))

(defn- same-sets?
  "Whether `a` and `b` hold the same set keys and the same tokens maps by
  identity, the common case of a repeated call."
  [a b]
  (or (identical? a b)
      (and (= (count a) (count b))
           (every? true? (map (fn [[ka ta] [kb tb]] (and (= ka kb) (identical? ta tb))) a b)))))

(defn- cached-entry
  [sets]
  (let [entries @cache]
    (or (some #(when (same-sets? (:sets %) sets) %) entries)
        (some #(when (= (:sets %) sets) %) entries))))

(defn- remember
  "Puts `entry` first, dropping the oldest beyond `cache-size`."
  [entry]
  (vswap! cache (fn [entries]
                  (into [entry]
                        (comp (remove #(identical? % entry)) (take (dec cache-size)))
                        entries)))
  entry)

(defn resolve-sets
  "Resolves the ordered token `sets`, `[[set-id tokens] ..]`, where `tokens`
  maps token names to tokens and a name defined by several sets is taken
  from the last one. Returns a map of the names to their tokens with
  `:resolved-value` and `:resolved-symbol`, or `:errors`, added.

  Resolutions are incremental: the interpreter state of the last few
  calls is kept, and a call re-resolves only the names whose token is not
  the same object as in the previous call and the tokens referencing
  them. The output keeps the previous entry for every other name, so the
  tokens an edit did not reach stay identical between calls."
  [sets]
  (:output (remember (or (cached-entry sets)
                         (resolve-sets* sets (first @cache))))))

(defn resolve-tokens
  "Resolves `tokens` (a map of token name to token), as one set. Returns
  the tokens with `:resolved-value` or `:errors` added. See `resolve-sets`."
  [tokens]
  (resolve-sets [[nil tokens]]))

(defn resolve-token
  "Resolves `token` against `tokens` (the token replaces any token with
  the same name). Returns the resolved token."
  [tokens token]
  (get (resolve-tokens (assoc tokens (:name token) token)) (:name token)))
