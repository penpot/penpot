;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   [app.common.files.tokens :as cft]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(l/set-level! :debug)

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
  (if-let [tc (tinycolor/valid-color value)]
    {:value value :unit (tinycolor/color-format tc)}
    {:errors [(wte/error-with-value :error.token/invalid-color value)]}))

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
        parsed-value  (cft/parse-token-value value)
        out-of-bounds (or (>= (:value parsed-value) sm/max-safe-int)
                          (<= (:value parsed-value) sm/min-safe-int))]

    (cond
      (and parsed-value (not out-of-bounds) number?)
      parsed-value

      out-of-bounds
      {:errors [(wte/error-with-value :error.token/number-too-large value)]}

      (seq (cto/find-token-value-references value))
      (let [references (seq (cto/find-token-value-references value))]
        {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
         :references references})

      (with-units value)
      {:errors [(wte/error-with-value :error.style-dictionary/value-with-units value)]}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-general-value
  "Parses `value` of a number `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [parsed-value  (cft/parse-token-value value)
        out-of-bounds (or (>= (:value parsed-value) sm/max-safe-int)
                          (<= (:value parsed-value) sm/min-safe-int))]
    (if (and parsed-value (not out-of-bounds))
      parsed-value
      (if out-of-bounds
        {:errors [(wte/error-with-value :error.token/number-too-large value)]}
        (if-let [references (seq (cto/find-token-value-references value))]
          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
           :references references}
          {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))))

(defn- parse-sd-token-opacity-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value]
  (let [missing-references? (seq (cto/find-token-value-references value))
        parsed-value (cft/parse-token-value value)
        out-of-scope (not (<= 0 (:value parsed-value) 1))
        references (seq (cto/find-token-value-references value))]
    (cond (and parsed-value (not out-of-scope))
          parsed-value

          references
          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
           :references references}

          (and (not missing-references?) out-of-scope)
          {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-opacity value)]}

          (and missing-references? out-of-scope parsed-value)
          (assoc parsed-value :warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-opacity value)])

          :else {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-stroke-width-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value]
  (let [missing-references? (seq (cto/find-token-value-references value))
        parsed-value (cft/parse-token-value value)
        out-of-scope (< (:value parsed-value) 0)
        references (seq (cto/find-token-value-references value))]
    (cond
      (and parsed-value (not out-of-scope))
      parsed-value

      references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      (and (not missing-references?) out-of-scope)
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-stroke-width value)]}

      (and missing-references? out-of-scope parsed-value)
      (assoc parsed-value :warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-stroke-width value)])

      :else {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))

(defn- parse-sd-token-letter-spacing-value
  "Parses `value` of a text-case `sd-token` into a map like `{:value \"1\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [parsed-value (parse-sd-token-general-value value)]
    (if (= (:unit parsed-value) "%")
      {:errors [(wte/error-with-value :error.style-dictionary/value-with-percent value)]}
      parsed-value)))

(defn- parse-sd-token-text-case-value
  "Parses `value` of a text-case `sd-token` into a map like `{:value \"uppercase\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [normalized-value (str/lower (str/trim value))
        valid? (contains? #{"none" "uppercase" "lowercase" "capitalize"} normalized-value)
        references (seq (cto/find-token-value-references value))]
    (cond
      valid?
      {:value normalized-value}

      references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-text-case value)]})))

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
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-text-decoration value)]})))

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
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-font-weight value)]})))

(defn- parse-sd-token-typography-line-height
  "Parses `line-height-value` of a composite typography token.
  Uses `font-size-value` to calculate the relative line-height value.
  Returns an error for an invalid font-size value."
  [line-height-value font-size-value font-size-errors]
  (let [missing-references (seq (some cto/find-token-value-references line-height-value))
        error
        (cond
          missing-references
          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference missing-references)]
           :references missing-references}

          (or
           (not font-size-value)
           (seq font-size-errors))
          {:errors [(wte/error-with-value :error.style-dictionary/composite-line-height-needs-font-size font-size-value)]
           :font-size-value font-size-value})]
    (or error
        (try
          (when-let [{:keys [unit value]} (cft/parse-token-value line-height-value)]
            (case unit
              "%" (/ value 100)
              "px" (/ value font-size-value)
              nil value
              nil))
          (catch :default _ nil))
        {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value line-height-value)]})))

(defn- parse-sd-token-font-family-value
  [value]
  (let [value (-> (js->clj value) (flatten))
        valid-font-family (or (string? value) (every? string? value))
        missing-references (seq (some cto/find-token-value-references value))]
    (cond
      (not valid-font-family)
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-font-family value)]}

      missing-references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference missing-references)]
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
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      (string? value)
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-typography value)]}

      :else
      (let [converted (js->clj value :keywordize-keys true)
            add-keyed-errors (fn [typography-map k errors]
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
        valid-typography))))

(defn collect-typography-errors [token]
  (group-by :typography-key (:errors token)))

(defn- parse-sd-token-shadow-inset
  [value]
  (let [references (seq (cto/find-token-value-references value))]
    (cond
      (boolean? value)
      {:value value}

      references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-shadow-type value)]})))

(defn- parse-sd-token-shadow-blur
  "Parses shadow blur value (non-negative number)."
  [value]
  (let [parsed (parse-sd-token-general-value value)
        valid? (and (:value parsed) (>= (:value parsed) 0))]
    (cond
      valid?
      parsed

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-shadow-blur value)]})))

(defn- parse-sd-token-shadow-spread
  "Parses shadow spread value (non-negative number)."
  [value]
  (let [parsed (parse-sd-token-general-value value)
        valid? (and (:value parsed) (>= (:value parsed) 0))]
    (cond
      valid?
      parsed

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-shadow-spread value)]})))

(defn- parse-single-shadow
  "Parses a single shadow map with properties: x, y, blur, spread, color, type."
  [shadow-map shadow-index]
  (let [add-keyed-errors (fn [shadow-result k errors]
                           (update shadow-result :errors concat
                                   (map #(assoc % :shadow-key k :shadow-index shadow-index) errors)))
        parsers {:offsetX parse-sd-token-general-value
                 :offsetY parse-sd-token-general-value
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
  (cond
    ;; Reference value (string)
    (string? value) {:value value}

    ;; Empty value
    (nil? value) {:errors [(wte/get-error-code :error.token/empty-input)]}

    ;; Invalid value
    (not (js/Array.isArray value)) {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]}

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
        {:value all-values}))))

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
     (let [origin-token (get-origin-token sd-token)
           value (.-value sd-token)
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
       (assoc acc (:name output-token) output-token)))
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
      (rx/from build+))))

(defn resolve-tokens-tree
  ([tokens-tree get-token]
   (resolve-tokens-tree tokens-tree get-token (StyleDictionary. default-config)))
  ([tokens-tree get-token style-dictionary]
   (->> (add-tokens style-dictionary tokens-tree)
        (build-dictionary)
        (rx/map #(process-sd-tokens % get-token)))))

(defn sd-token-name [^js sd-token]
  (.. sd-token -original -name))

(defn sd-token-uuid [^js sd-token]
  (uuid (.-uuid (.-id ^js sd-token))))

(defn resolve-tokens
  [tokens]
  (let [tokens-tree (ctob/tokens-tree tokens)]
    (resolve-tokens-tree tokens-tree #(get tokens (sd-token-name %)))))

(defn resolve-tokens-interactive
  "Interactive check of resolving tokens.
  Uses a ids map to backtrace the original token from the resolved StyleDictionary token.

  We have to pass in all tokens from all sets in the entire library to style dictionary
  so we know if references are missing / to resolve them and possibly show interactive previews (in the tokens form) to the user.

  Since we're using the :name path as the identifier we might be throwing away or overriding tokens in the tree that we pass to StyleDictionary.

  So to get back the original token from the resolved sd-token (see my updates for what an sd-token is) we include a temporary :id for the token that we pass to StyleDictionary,
  this way after the resolving computation we can restore any token, even clashing ones with the same :name path by just looking up that :id in the ids map."
  [tokens]
  (let [{:keys [tokens-tree ids]} (ctob/backtrace-tokens-tree tokens)]
    (resolve-tokens-tree tokens-tree  #(get ids (sd-token-uuid %)))))

(defn resolve-tokens-with-verbose-errors [tokens]
  (resolve-tokens-tree
   (ctob/tokens-tree tokens)
   #(get tokens (sd-token-name %))
   (StyleDictionary. (assoc default-config :log {:verbosity "verbose"}))))

;; === Hooks

(defonce !tokens-cache (atom nil))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use resolved values directly.

  This hook will return the unresolved tokens as state until they are processed,
  then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom interactive?]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]

    ;; FIXME: this with effect with trigger all the time because
    ;; `config` will be always a different instance
    (mf/with-effect [tokens config]
      (let [cached (get @cache-atom tokens)]
        (cond
          (nil? tokens) nil
          ;; The tokens are already processing somewhere
          (rx/observable? cached) (rx/sub! cached #(reset! tokens-state %))
          ;; Get the cached entry
          (some? cached) (reset! tokens-state cached)
          ;; No cached entry, start processing
          :else (let [resolved-tokens-s (if interactive?
                                          (resolve-tokens-interactive tokens)
                                          (resolve-tokens tokens))]
                  (swap! cache-atom assoc tokens resolved-tokens-s)
                  (rx/sub! resolved-tokens-s (fn [resolved-tokens]
                                               (swap! cache-atom assoc tokens resolved-tokens)
                                               (reset! tokens-state resolved-tokens)))))))
    @tokens-state))

(defn use-resolved-tokens*
  "This hook will return the unresolved tokens as state until they are
  processed, then the state will be updated with the resolved tokens.

  This is a cache-less, simplified version of use-resolved-tokens
  hook."
  [tokens & {:keys [interactive?]}]
  (let [state* (mf/use-state tokens)]
    (mf/with-effect [tokens interactive?]
      (if (seq tokens)
        (let [tpoint  (ct/tpoint-ms)
              tokens-s  (if interactive?
                          (resolve-tokens-interactive tokens)
                          (resolve-tokens tokens))]

          (-> tokens-s
              (rx/sub! (fn [resolved-tokens]
                         (let [elapsed (tpoint)]
                           (l/dbg :hint "use-resolved-tokens*" :elapsed elapsed)
                           (reset! state* resolved-tokens))))))
        (reset! state* tokens)))
    @state*))
