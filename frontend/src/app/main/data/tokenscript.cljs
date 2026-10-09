;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.tokenscript
  "Resolves the workspace tokens with the tokenscript interpreter
  (`app.common.tokenscript`).

  `resolve-tokens` takes the usual map of token name to token and returns
  the same map with `:resolved-value` (a value ready for shape attributes:
  numbers in pixels, colors as hex strings, composites as maps),
  `:resolved-symbol` (the tokenscript value, see
  `app.common.tokenscript.symbols`) and `:errors` (Penpot error maps, see
  `app.main.data.workspace.tokens.errors`) added to each token."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.errors :as tse]
   [app.common.tokenscript.symbols :as sym]
   [app.common.types.token :as cto]
   [app.main.data.workspace.tokens.errors :as wte]
   [clojure.set :as set]
   [cuerdas.core :as str]))

(l/set-level! :debug)

;; Value conversion -----------------------------------------------------------

(defn- color->hex-string
  "Hex string of a color symbol (`#rrggbbaa` when it has alpha)."
  [color]
  (let [cfg (tsc/default-config)]
    (sym/to-string (tsc/color->hex cfg color) cfg)))

(declare symbol->value)

(defn- entries->map
  [entries]
  (into {} (map (fn [[k v]] [(keyword k) (symbol->value v)])) entries))

(defn symbol->value
  "Converts a resolved tokenscript value into the shape used by shape
  attributes and the token forms:

    - colors: hex strings (alpha as `#rrggbbaa`)
    - `rem` values: pixels (16px root font size), `%` values: fractions
    - other numbers with unit: the number
    - structured tokens (typography, shadows): maps with keyword keys and
      vectors
    - null: nil"
  [v]
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
    :else v))

(defn format-value
  "String form of a resolved tokenscript value, for display."
  [v]
  (if (sym/symbol? v)
    (sym/to-string v (tsc/default-config))
    (str v)))

;; Value validation -----------------------------------------------------------
;;
;; Tokenscript resolves any expression; these are the Penpot level checks
;; on the resolved value of each token type (ranges, allowed keywords,
;; composite shapes) that decide whether a token can be applied.

(defn- out-of-bounds?
  [n]
  (or (>= n sm/max-safe-int) (<= n sm/min-safe-int)))

(def ^:private text-case-values
  #{"none" "uppercase" "lowercase" "capitalize"})

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
  text attributes expect. With `line-height-ratio?` a line height in
  pixels is converted relative to the font size."
  [s line-height-ratio?]
  (let [value (symbol->value s)]
    (if-not (map? value)
      {:value value}
      (let [value (cond-> value
                    (contains? value :font-family) (update :font-family font-family->vector)
                    (contains? value :font-weight) (update :font-weight words->string))
            line-height (when (and line-height-ratio? (sym/token? s)) (get (sym/value s) "line-height"))]
        (if (some? line-height)
          (let [{:keys [value* error]} (-> (line-height->ratio line-height (:font-size value))
                                           (set/rename-keys {:value :value*}))]
            (if error
              {:error error}
              {:value (assoc value :line-height value*)}))
          {:value value})))))

(defn- convert-value
  "Converts the resolved symbol of a token to the value shape expected by
  its type. Returns `{:value v}` or `{:error e}`. Options:

    :line-height-ratio? convert typography line heights in pixels to a
                        ratio of the font size (default true)"
  ([type s] (convert-value type s nil))
  ([type s {:keys [line-height-ratio?] :or {line-height-ratio? true}}]
   (case type
     :typography (typography->value s line-height-ratio?)
     :font-family {:value (font-family->vector (symbol->value s))}
     :font-weight {:value (words->string (symbol->value s))}
     {:value (symbol->value s)})))

(defn plugin-value
  "Resolved value of a token as exposed by the plugin API: like
  `:resolved-value` but with typography line heights left as resolved."
  [{:keys [type resolved-symbol]}]
  (when (some? resolved-symbol)
    (:value (convert-value type resolved-symbol {:line-height-ratio? false}))))

(defn- validate-value
  "Returns a Penpot error for a resolved `value` of a token `type`, or nil
  when it is acceptable."
  [type value]
  (if (and (number? value) (out-of-bounds? value))
    (wte/error-with-value :error.token/number-too-large value)
    (case type
      :opacity
      (when-not (and (number? value) (<= 0 value 1))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-opacity value))

      :stroke-width
      (when-not (and (number? value) (>= value 0))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-stroke-width value))

      :text-case
      (when-not (and (string? value) (contains? text-case-values (str/lower (str/trim value))))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-text-case value))

      :text-decoration
      (when-not (and (string? value) (cto/valid-text-decoration value))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-text-decoration value))

      :font-weight
      (when-not (cto/valid-font-weight-variant (str value))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-font-weight value))

      :font-family
      (when-not (or (string? value) (and (sequential? value) (every? string? value)))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-font-family value))

      :typography
      (when-not (map? value)
        (wte/error-with-value :error.style-dictionary/invalid-token-value-typography value))

      :shadow
      (when-not (and (sequential? value) (every? map? value))
        (wte/error-with-value :error.style-dictionary/invalid-token-value-shadow value))

      nil)))

;; Errors ---------------------------------------------------------------------

(defn- missing-reference-of
  "Missing token name behind an issue, if the root cause is a missing
  token."
  [issue]
  (let [{:keys [root-cause chain token-name]} (:data issue)]
    (case (:code issue)
      :processor/token-not-found token-name
      :interpreter/unknown-reference (get-in issue [:data :reference])
      :processor/dependency-error (when (and (string? root-cause) (str/starts-with? root-cause "Token '"))
                                    (last (str/split (str chain) #" → ")))
      nil)))

(defn- circular?
  [issue]
  (or (= :processor/circular-dependency (:code issue))
      (and (= :processor/dependency-error (:code issue))
           (str/starts-with? (str (get-in issue [:data :root-cause])) "Circular dependency"))))

(defn issue->error
  "Maps a tokenscript issue to a Penpot token error."
  [issue value]
  (cond
    (circular? issue)
    (wte/error-with-value :error.token/circular-reference nil)

    (missing-reference-of issue)
    (wte/error-with-value :error.style-dictionary/missing-reference [(missing-reference-of issue)])

    :else
    (-> (wte/error-with-value :error.style-dictionary/invalid-token-value value)
        (assoc :error/detail-message (or (:hint issue) (:message issue))))))

(defn- token-errors
  [issues value]
  (->> issues
       (filter #(or (tse/error-map? %) (= :error (:severity %))))
       (map #(issue->error % value))
       (seq)))

;; Resolution -----------------------------------------------------------------

(defn- token->input
  [{:keys [type value]}]
  (cond-> {:value value}
    (some? type) (assoc :type (name type))))

(defn- empty-value?
  [token]
  (nil? (:value token)))

(defn- resolve-tokens*
  [tokens]
  (let [tpoint (ct/tpoint-ms)
        inputs (into {} (comp (remove (fn [[_ t]] (empty-value? t)))
                              (map (fn [[name token]] [name (token->input token)])))
                     tokens)
        result (tsc/build inputs)
        resolved (:tokens result)
        issues (:issues result)
        output (reduce-kv
                (fn [acc name token]
                  (assoc acc name
                         (if (empty-value? token)
                           (assoc token :errors [(wte/get-error-code :error.token/empty-input)])
                           (let [errors (token-errors (get issues name) (:value token))
                                 {:keys [value error]} (when-not errors (convert-value (:type token) (get resolved name)))
                                 error (or error (when-not errors (validate-value (:type token) value)))]
                             (cond
                               errors (assoc token :errors (vec errors))
                               error (assoc token :errors [error])
                               :else (assoc token
                                            :resolved-value value
                                            :resolved-symbol (get resolved name)))))))
                {}
                tokens)]
    (l/dbg :hint "tokenscript/resolve-tokens" :elapsed (tpoint))
    output))

;; Several components resolve the same token maps on every change (the
;; sidebar, the forms and the propagation each compute the active tokens
;; separately), so the results of the last few maps are kept. Entries are
;; matched by identity first and by equality second: comparing two maps
;; of a few hundred tokens is far cheaper than resolving them again.
(def ^:private cache-size 8)

(defonce ^:private results-cache (volatile! []))

(defn- cached-result
  [tokens]
  (let [entries @results-cache]
    (or (some (fn [[t result]] (when (identical? t tokens) result)) entries)
        (some (fn [[t result]] (when (= t tokens) result)) entries))))

(defn- remember-result
  [tokens result]
  (vswap! results-cache (fn [entries] (vec (take cache-size (cons [tokens result] entries))))))

(defn resolve-tokens
  "Resolves `tokens` (a map of token name to token). Returns the tokens
  with `:resolved-value` or `:errors` added."
  [tokens]
  (or (cached-result tokens)
      (let [result (resolve-tokens* tokens)]
        (remember-result tokens result)
        result)))

(defn resolve-token
  "Resolves `token` against `tokens` (the token replaces any token with
  the same name). Returns the resolved token."
  [tokens token]
  (get (resolve-tokens (assoc tokens (:name token) token)) (:name token)))

(defn resolved-color
  "`{:color hex :opacity alpha}` for a resolved color value string."
  [value]
  (when (string? value)
    (if (= 9 (count value))
      {:color (subs value 0 7)
       :opacity (/ (js/parseInt (subs value 7 9) 16) 255)}
      {:color value :opacity 1})))
