;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.tokens-resolution
  "Resolution of the tokens with StyleDictionary, for the workspace and for
  the readers of a file (viewer, exporter, thumbnails).

  The functions return promises. Errors and warnings are data, a map with
  `:error/code` (or `:warning/code`) and the value that caused them; the
  workspace adds the messages to show."
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   ["tinycolor2$default" :as tinycolor]
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.logging :as l]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.logic.tokenscript :as cts]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :warn)

(defn error
  "Error of the resolution of a token, with the `value` that caused it."
  ([code] {:error/code code})
  ([code value] {:error/code code :error/value value}))

(defn warning
  "Warning of the resolution of a token, with the `value` that caused it."
  [code value]
  {:warning/code code :warning/value value})

(defn- valid-color
  "A tinycolor of `color-str` when it is a valid css color."
  [color-str]
  (let [tc (tinycolor color-str)]
    (when (and (.isValid ^js tc)
               ;; Invalid CSS color strings return `false` for `.getFormat`
               (.getFormat ^js tc)
               ;; Values like `1111` give a `hex8` format but aren't valid
               ;; css colors, so hex values need the # prefix
               (if (str/starts-with? (.getFormat ^js tc) "hex")
                 (str/starts-with? (.getOriginalInput ^js tc) "#")
                 true))
      tc)))

(defn- color-format
  [^js tc]
  (.getFormat tc))

;; === Style Dictionary

(def setup-style-dictionary
  "Initiates the StyleDictionary instance.
  Setup transforms from tokens-studio used to parse and resolved token values."
  (do
    (sd-transforms/register sd)
    (.registerTransformGroup sd #js {:name "penpot"
                                     :transforms
                                     ;; Rebuild sd-transforms without "ts/typography/compose/shorthand" (we need to keep a typography map)
                                     (.concat (sd-transforms/getTransforms)
                                              #js ["ts/color/css/hexrgba"
                                                   "ts/color/modifiers"
                                                   "color/css"])})
    (.registerFormat sd #js {:name "custom/json"
                             :format (fn [^js res]
                                       (.. res -dictionary -tokens))})
    sd))

(def default-config
  {:platforms {:json
               {:transformGroup "penpot"
                ;; Required: The StyleDictionary API is focused on files even when working in the browser
                :files [{:format "custom/json" :destination "penpot"}]}}
   :preprocessors ["tokens-studio"]
   ;; Silences style dictionary logs and errors
   ;; We handle token errors in the UI
   :log {:verbosity "silent"
         :warnings "silent"
         :errors {:brokenReferences "console"}}})

(defn- parse-sd-token-color-value
  "Parses `value` of a color `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the value is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [missing-references (seq (cto/find-token-value-references value))]
    (if-let [tc (valid-color value)]
      {:value value :unit (color-format tc)}
      (cond
        missing-references
        {:errors [(error :error.style-dictionary/missing-reference missing-references)]
         :references missing-references}
        :else
        {:errors [(error :error.token/invalid-color value)]}))))

(defn- numeric-string? [s]
  (and (string? s)
       (re-matches #"^-?\d+(\.\d+)?$" s)))

(defn- with-units [s]
  (and (string? s)
       (re-matches #"^-?\d+(\.\d+)?(px|rem)$" s)))

(defn- parse-sd-token-number-value
  "Parses `value` of a number `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [number? (or (number? value)
                    (numeric-string? value))
        parsed-value  (cfo/parse-token-value value)
        out-of-bounds (cto/number-out-of-bounds? (:value parsed-value))]

    (cond
      (and parsed-value (not out-of-bounds) number?)
      parsed-value

      out-of-bounds
      {:errors [(error :error.token/number-too-large value)]}

      (seq (cto/find-token-value-references value))
      (let [references (seq (cto/find-token-value-references value))]
        {:errors [(error :error.style-dictionary/missing-reference references)]
         :references references})

      (with-units value)
      {:errors [(error :error.style-dictionary/value-with-units value)]}

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-general-value
  "Parses `value` of a `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [parsed-value  (cfo/parse-token-value value)
        out-of-bounds (cto/number-out-of-bounds? (:value parsed-value))]
    (if (and parsed-value (not out-of-bounds))
      parsed-value
      (if out-of-bounds
        {:errors [(error :error.token/number-too-large value)]}
        (if-let [references (seq (cto/find-token-value-references value))]
          {:errors [(error :error.style-dictionary/missing-reference references)]
           :references references}
          {:errors [(error :error.style-dictionary/invalid-token-value value)]})))))

(defn- parse-sd-token-opacity-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value]
  (let [missing-references? (seq (cto/find-token-value-references value))
        parsed-value (cfo/parse-token-value value)
        out-of-scope (not (<= 0 (:value parsed-value) 1))
        references (seq (cto/find-token-value-references value))]
    (cond (and parsed-value (not out-of-scope))
          parsed-value

          references
          {:errors [(error :error.style-dictionary/missing-reference references)]
           :references references}

          (and (not missing-references?) out-of-scope)
          {:errors [(error :error.style-dictionary/invalid-token-value-opacity value)]}

          (and missing-references? out-of-scope parsed-value)
          (assoc parsed-value :warnings [(warning :warning.style-dictionary/invalid-referenced-token-value-opacity value)])

          :else {:errors [(error :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-stroke-width-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value]
  (let [missing-references? (seq (cto/find-token-value-references value))
        parsed-value (cfo/parse-token-value value)
        out-of-scope (< (:value parsed-value) 0)]
    (cond
      (and parsed-value (not out-of-scope))
      parsed-value

      missing-references?
      {:errors [(error :error.style-dictionary/missing-reference missing-references?)]
       :references missing-references?}

      (and (not missing-references?) out-of-scope)
      {:errors [(error :error.style-dictionary/invalid-token-value-stroke-width value)]}

      (and missing-references? out-of-scope parsed-value)
      (assoc parsed-value :warnings [(warning :warning.style-dictionary/invalid-referenced-token-value-stroke-width value)])

      :else {:errors [(error :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-letter-spacing-value
  "Parses `value` of a text-case `sd-token` into a map like `{:value \"1\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [parsed-value (parse-sd-token-general-value value)]
    (if (= (:unit parsed-value) "%")
      {:errors [(error :error.style-dictionary/value-with-percent value)]}
      parsed-value)))

(defn- parse-sd-token-text-case-value
  "Parses `value` of a text-case `sd-token` into a map like `{:value \"uppercase\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [normalized-value (str/lower (str/trim value))
        valid? (contains? cto/text-case-values normalized-value)
        references (seq (cto/find-token-value-references value))]
    (cond
      valid?
      {:value normalized-value}

      references
      {:errors [(error :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-text-case value)]})))

(defn- parse-sd-token-text-decoration-value
  "Parses `value` of a text-decoration `sd-token` into a map like `{:value \"underline\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [valid-text-decoration (cto/valid-text-decoration value)
        references (seq (cto/find-token-value-references value))]
    (cond
      valid-text-decoration
      {:value valid-text-decoration}

      references
      {:errors [(error :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-text-decoration value)]})))

(defn- parse-sd-token-font-weight-value
  "Parses `value` of a font-weight `sd-token` into a map like `{:value \"700\"}` or `{:value \"700 Italic\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [valid-font-weight (cto/valid-font-weight-variant value)
        references (seq (cto/find-token-value-references value))]
    (cond
      valid-font-weight
      {:value value}

      references
      {:errors [(error :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-font-weight value)]})))

(defn- parse-sd-token-typography-line-height
  "Parses `line-height-value` of a composite typography token.
  Uses `font-size-value` to calculate the relative line-height value.
  Returns an error for an invalid font-size value."
  [line-height-value font-size-value font-size-errors]
  (let [missing-references (seq (cto/find-token-value-references line-height-value))
        error
        (cond
          missing-references
          {:errors [(error :error.style-dictionary/missing-reference missing-references)]
           :references missing-references}

          (or
           (not font-size-value)
           (seq font-size-errors))
          {:errors [(error :error.style-dictionary/composite-line-height-needs-font-size font-size-value)]
           :font-size-value font-size-value})]
    (or error
        (try
          (when-let [{:keys [unit value]} (cfo/parse-token-value line-height-value)]
            (case unit
              "%" (/ value 100)
              "px" (/ value font-size-value)
              nil value
              nil))
          (catch :default _ nil))
        {:errors [(error :error.style-dictionary/invalid-token-value line-height-value)]})))

(defn- parse-sd-token-font-family-value
  [value]
  (let [value (js->clj value)
        ;; A family list, or a string with the families separated by commas
        value (if (string? value) (cto/split-font-family value) (flatten value))
        valid-font-family (or (string? value) (every? string? value))
        missing-references (seq (some cto/find-token-value-references value))]
    (cond
      (not valid-font-family)
      {:errors [(error :error.style-dictionary/invalid-token-value-font-family value)]}

      missing-references
      {:errors [(error :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      :else
      {:value value})))

(defn parse-atomic-typography-value [token-type token-value]
  (case token-type
    :font-size (parse-sd-token-general-value token-value)
    :font-family (parse-sd-token-font-family-value token-value)
    :font-weight (parse-sd-token-font-weight-value token-value)
    :letter-spacing (parse-sd-token-letter-spacing-value token-value)
    :text-case (parse-sd-token-text-case-value token-value)
    :text-decoration (parse-sd-token-text-decoration-value token-value)
    nil))

(defn- parse-composite-typography-value
  "Parses composite typography `value` map.
  Processes the `:line-height` based on the `:font-size` value in the map."
  [value]
  (let [missing-references
        (when (string? value)
          (seq (cto/find-token-value-references value)))]
    (cond
      missing-references
      {:errors [(error :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      (string? value)
      {:errors [(error :error.style-dictionary/invalid-token-value-typography value)]}

      :else
      (let [converted (js->clj value :keywordize-keys true)]
        (if-not (map? converted)
          {:errors [(error :error.style-dictionary/invalid-token-value-typography value)]}
          (let [add-keyed-errors (fn [typography-map k errors]
                                   (update typography-map :errors concat (map #(assoc % :typography-key k) errors)))
                ;; Separate line-height to process in an extra step
                without-line-height (dissoc converted :line-height)
                valid-typography (reduce
                                  (fn [acc [k v]]
                                    (let [{:keys [errors value]} (parse-atomic-typography-value k v)]
                                      (if (seq errors)
                                        (add-keyed-errors acc k errors)
                                        (assoc-in acc [:value k] (or value v)))))
                                  {:value {}}
                                  without-line-height)

                ;; Calculate line-height based on the resolved font-size and add it back to the map
                line-height (when-let [line-height (:line-height converted)]
                              (-> (parse-sd-token-typography-line-height
                                   line-height
                                   (get-in valid-typography [:value :font-size])
                                   (get-in valid-typography [:errors :font-size]))))
                valid-typography (cond
                                   (:errors line-height)
                                   (add-keyed-errors valid-typography :line-height (:errors line-height))

                                   line-height
                                   (assoc-in valid-typography [:value :line-height] line-height)

                                   :else
                                   valid-typography)]
            valid-typography))))))

(defn collect-typography-errors [token]
  (group-by :typography-key (:errors token)))

(defn- parse-sd-token-shadow-inset
  [value]
  (let [references (seq (cto/find-token-value-references value))]
    (cond
      (boolean? value)
      {:value value}

      references
      {:errors [(error :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-shadow-type value)]})))

(defn- parse-sd-token-shadow-blur
  "Parses shadow blur value (non-negative number)."
  [value]
  (let [parsed (parse-sd-token-general-value value)
        valid? (and (:value parsed) (>= (:value parsed) 0))]
    (cond
      valid?
      parsed

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-shadow-blur value)]})))

(defn- parse-sd-token-shadow-spread
  "Parses shadow spread value (non-negative number)."
  [value]
  (let [parsed (parse-sd-token-general-value value)
        valid? (:value parsed)]
    (cond
      valid?
      parsed

      :else
      {:errors [(error :error.style-dictionary/invalid-token-value-shadow-spread value)]})))

(defn- parse-single-shadow
  "Parses a single shadow map with properties: x, y, blur, spread, color, type."
  [shadow-map shadow-index]
  (let [shadow-map (merge {:offset-x nil   ;; Ensure that all keys are processed, even if missing in the original token
                           :offset-y nil
                           :blur nil
                           :spread nil
                           :color nil
                           :inset false}
                          shadow-map)

        add-keyed-errors (fn [shadow-result k errors]
                           (update shadow-result :errors concat
                                   (map #(assoc % :shadow-key k :shadow-index shadow-index) errors)))
        parsers {:offset-x parse-sd-token-general-value
                 :offset-y parse-sd-token-general-value
                 :blur parse-sd-token-shadow-blur
                 :spread parse-sd-token-shadow-spread
                 :color parse-sd-token-color-value
                 :inset parse-sd-token-shadow-inset}
        valid-shadow (reduce
                      (fn [acc [k v]]
                        (if-let [parser (get parsers k)]
                          (let [{:keys [errors value]} (parser v)]
                            (if (seq errors)
                              (add-keyed-errors acc k errors)
                              (assoc-in acc [:value k] (or value v))))
                          acc))
                      {:value {}}
                      shadow-map)]
    valid-shadow))

(defn- parse-sd-token-shadow-value
  "Parses shadow value and validates it."
  [value]
  (let [missing-references
        (when (string? value)
          (seq (cto/find-token-value-references value)))]
    (cond
      missing-references
      {:errors [(error :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      (string? value)
      {:errors [(error :error.style-dictionary/invalid-token-value-shadow value)]}

      ;; Empty value
      (nil? value) {:errors [(error :error.token/empty-input)]}

      ;; Invalid value
      (not (js/Array.isArray value)) {:errors [(error :error.style-dictionary/invalid-token-value value)]}

      ;; Array of shadows
      :else
      (let [converted (js->clj value :keywordize-keys true)
            ;; Parse each shadow with its index
            parsed-shadows (map-indexed
                            (fn [idx shadow-map]
                              (parse-single-shadow shadow-map idx))
                            converted)

            ;; Collect all errors from all shadows
            all-errors (mapcat :errors parsed-shadows)

            ;; Collect all values from shadows that have values
            all-values (into [] (keep :value parsed-shadows))]

        (if (seq all-errors)
          {:errors all-errors
           :value all-values}
          {:value all-values})))))

(defn collect-shadow-errors [token shadow-index]
  (group-by :shadow-key
            (filter #(= (:shadow-index %) shadow-index)
                    (:errors token))))

(defn process-sd-tokens
  "Converts a StyleDictionary dictionary with resolved tokens (aka `sd-tokens`) back to clojure.
  The `get-origin-token` argument should be a function that takes an
  `sd-token` and returns the original penpot token, so we can merge
  the resolved attributes back in.

  The `sd-token` will have references in `value` replaced with the computed value as a string.
  Here's an example for a `sd-token`:
  ```js
  {
    name:  'token.with.reference',
    value: '12px',
    type:  'border-radius',
    path: ['token', 'with', 'reference'],

    // The penpot origin token converted to a js object
    original: {
        name:  'token.with.reference',
        value: '{referenced.token}',
        type:  'border-radius'
    },
  }
  ```

  We also convert `sd-token` value string into a unit that can be used as penpot shape attributes.
    - Dimensions like '12px' will be converted into numbers
    - Colors will be validated & converted to hex

  Lastly we check for errors in each token
  `sd-token` will keep the missing references in the `value` (E.g \"{missing} + {existing}\" -> \"{missing} + 12px\")
  So we parse out the missing references and add them to `:errors` in the final token."
  [sd-tokens get-origin-token]
  (reduce
   (fn [acc ^js sd-token]
     (let [origin-token (get-origin-token sd-token)]
       (if (nil? origin-token)
         ;; Skip group nodes that StyleDictionary returns alongside
         ;; actual tokens — they have no matching origin token.
         acc
         (let [value (.-value sd-token)
               parsed-token-value (or
                                   (parse-atomic-typography-value (:type origin-token) value)
                                   (case (:type origin-token)
                                     :typography (parse-composite-typography-value value)
                                     :shadow (parse-sd-token-shadow-value value)
                                     :color (parse-sd-token-color-value value)
                                     :opacity (parse-sd-token-opacity-value value)
                                     :stroke-width (parse-sd-token-stroke-width-value value)
                                     :number (parse-sd-token-number-value value)
                                     (parse-sd-token-general-value value)))
               output-token (cond (:errors parsed-token-value)
                                  (merge origin-token parsed-token-value)

                                  (:warnings parsed-token-value)
                                  (assoc origin-token
                                         :resolved-value (:value parsed-token-value)
                                         :warnings (:warnings parsed-token-value)
                                         :unit (:unit parsed-token-value))

                                  :else
                                  (assoc origin-token
                                         :resolved-value (:value parsed-token-value)
                                         :unit (:unit parsed-token-value)))]
           (assoc acc (:name output-token) output-token)))))
   {} sd-tokens))

(defprotocol IStyleDictionary
  (add-tokens [_ tokens])
  (enable-debug [_])
  (get-config [_])
  (build-dictionary [_]))

(deftype StyleDictionary [config]
  IStyleDictionary
  (add-tokens [_ tokens]
    (StyleDictionary. (assoc config :tokens tokens)))

  (enable-debug [_]
    (StyleDictionary. (update config :log merge {:verbosity "verbose"})))

  (get-config [_]
    config)

  (build-dictionary [_]
    (let [platform "json"
          config' (clj->js config)
          build+ (-> (sd. config')
                     (.buildAllPlatforms platform)
                     (p/then #(.getPlatformTokens ^js % platform))
                     (p/then #(.-allTokens ^js %)))]
      build+)))

(defn resolve-tokens-tree
  ([tokens-tree get-token]
   (resolve-tokens-tree tokens-tree get-token (StyleDictionary. default-config)))
  ([tokens-tree get-token style-dictionary]
   (->> (add-tokens style-dictionary tokens-tree)
        (build-dictionary)
        (p/fmap #(process-sd-tokens % get-token)))))

(defn sd-token-name [^js sd-token]
  (.. sd-token -original -name))

(defn sd-token-uuid [^js sd-token]
  (when-let [id (.. sd-token -original -id)]
    (uuid (.-uuid id))))

(defn- merge-name-collisions
  "Re-attach tokens that `ctob/tokens-tree` / `backtrace-tokens-tree`
  dropped because one token's name is a strict prefix of another's
  (e.g. `a` vs `a.b` coming from two active sets). `assoc-in` in the
  tree builder collapses such pairs, so StyleDictionary only sees one
  of them and the other vanishes from the resolved map — and from the
  sidebar, even though it still lives in the library (#9584).

  We tag each dropped token with `:error.token/name-collision` so the
  existing token-pill error rendering picks them up as broken pills,
  matching the expected behaviour in the issue. Resolved tokens are
  left untouched."
  [tokens resolved]
  (let [dropped (->> tokens
                     (remove (fn [[k _]] (contains? resolved k)))
                     (map (fn [[k token]]
                            [k (assoc token
                                      :errors
                                      [(error
                                        :error.token/name-collision
                                        (:name token))])]))
                     (into {}))]
    (merge resolved dropped)))

(defn- valid-token-value?
  [[_ token]]
  (some? (:value token)))

(def ^:private xform-invalid-value-tokens
  (comp
   (remove valid-token-value?)
   (map (fn [[k token]]
          [k (assoc token :errors [(error :error.token/empty-input)])]))))

(defn- merge-invalid-value-tokens
  "Tokens with a `nil` value (e.g. a composite typography token saved with
  no fields filled in) must never reach StyleDictionary: some of its
  preprocessors (`@tokens-studio/sd-transforms`'s font-styles preprocessor,
  in particular) assume a typography token's value is never null and throw
  an uncaught exception when it is, taking down token resolution for the
  whole file.

  `tokens` is the full, unfiltered token map; `resolved` only contains the
  valid subset that was actually sent to StyleDictionary. Tag the invalid
  ones with the same \"empty value\" error the token forms already use
  instead of ever letting them reach the resolver."
  [tokens resolved]
  (into resolved xform-invalid-value-tokens tokens))

(defn resolve-tokens
  [tokens]
  (let [valid-tokens (into {} (filter valid-token-value?) tokens)
        tokens-tree  (ctob/tokens-tree valid-tokens)]
    (->> (resolve-tokens-tree tokens-tree #(get valid-tokens (sd-token-name %)))
         (p/fmap #(merge-name-collisions valid-tokens %))
         (p/fmap #(merge-invalid-value-tokens tokens %)))))

(defn resolve-tokens-interactive
  "Interactive check of resolving tokens.
  Uses a ids map to backtrace the original token from the resolved
  StyleDictionary token.

  We have to pass in all tokens from all sets in the entire library to
  style dictionary so we know if references are missing / to resolve
  them and possibly show interactive previews (in the tokens form) to
  the user.

  Since we're using the :name path as the identifier we might be
  throwing away or overriding tokens in the tree that we pass to
  StyleDictionary.

  So to get back the original token from the resolved sd-token (see my
  updates for what an sd-token is) we include a temporary :id for the
  token that we pass to StyleDictionary, this way after the resolving
  computation we can restore any token, even clashing ones with the
  same :name path by just looking up that :id in the ids map."
  [tokens]
  (let [valid-tokens (into {} (filter valid-token-value?) tokens)
        {:keys [tokens-tree ids]} (ctob/backtrace-tokens-tree valid-tokens)]
    (->> (resolve-tokens-tree tokens-tree #(get ids (sd-token-uuid %)))
         (p/fmap #(merge-name-collisions valid-tokens %))
         (p/fmap #(merge-invalid-value-tokens tokens %)))))

(defn resolve-tokens-with-verbose-errors [tokens]
  (let [valid-tokens (into {} (filter valid-token-value?) tokens)]
    (resolve-tokens-tree
     (ctob/tokens-tree valid-tokens)
     #(get valid-tokens (sd-token-name %))
     (StyleDictionary. (assoc default-config :log {:verbosity "verbose"})))))

;; === Readers

(defn- resolved?
  [token]
  (and (empty? (:errors token))
       (some? (:resolved-value token))))

(defn- resolve-with
  "Promise of `tokens` resolved with tokenscript or with StyleDictionary."
  [tokens tokenscript? base-font-size]
  (if tokenscript?
    (p/do (binding [cts/*base-font-size* (d/parse-double base-font-size 16)]
            (cts/resolve-tokens tokens)))
    (resolve-tokens tokens)))

(defn resolve-for-reader
  "Promise of the resolved tokens a reader sets its shapes with: `tokens`,
  the tokens its pages use (a map of name to `{:name :type :value}`),
  resolved with the same resolver as the workspace (`:tokenscript?`) and,
  with tokenscript, the file's `:base-font-size` as the rem base. A token
  that doesn't resolve takes its value in `saved`, the values saved in the
  file by the editors, and only those are used when the resolution
  fails."
  [tokens saved {:keys [tokenscript? base-font-size]}]
  (let [fallback (cltp/values->resolved-tokens saved)]
    (if (seq tokens)
      (-> (resolve-with tokens tokenscript? base-font-size)
          (p/then (fn [resolved]
                    (into fallback (filter (comp resolved? val)) resolved)))
          (p/catch (fn [cause]
                     (l/wrn :hint "unable to resolve the tokens, using the saved values"
                            :cause cause)
                     fallback)))
      (p/resolved fallback))))

(defn differs-from-saved?
  "True when some token that the shapes of `page`, a page fetched by a
  reader, apply has in `resolved` another value than the one saved by the
  editors (`:tokens-resolved`): a part of the page that the backend found
  up to date with the saved values may be out of date. The tokens only
  referenced by them have no saved value, so they are left out."
  [page resolved]
  (let [saved (:tokens-resolved page)]
    (boolean
     (some (fn [token-name]
             (when-let [token (get resolved token-name)]
               (not= (:resolved-value token) (get saved token-name))))
           (cfo/page-token-names page)))))

(defn resolve-page-for-reader
  "Promise of the resolved tokens of `page`, a page fetched by a reader with
  its `:tokens`, `:tokens-resolved` and `:base-font-size` (see
  `resolve-for-reader`)."
  [page tokenscript?]
  (resolve-for-reader (:tokens page)
                      (:tokens-resolved page)
                      {:tokenscript? tokenscript?
                       :base-font-size (:base-font-size page)}))

(defn refetch-whole?
  "True when `page`, a part of a page fetched by a reader, may be out of
  date with `resolved` although the backend found it up to date, so the
  reader fetches the whole page to set it."
  [page resolved]
  (and (not (:tokens-out-of-date page))
       (differs-from-saved? page resolved)))
