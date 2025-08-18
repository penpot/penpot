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
   [app.common.types.token :as ctt]
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

      (seq (ctob/find-token-value-references value))
      (let [references (seq (ctob/find-token-value-references value))]
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
        (if-let [references (seq (ctob/find-token-value-references value))]
          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
           :references references}
          {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))))

(defn- parse-sd-token-opacity-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value]
  (let [missing-references? (seq (ctob/find-token-value-references value))
        parsed-value (cft/parse-token-value value)
        out-of-scope (not (<= 0 (:value parsed-value) 1))
        references (seq (ctob/find-token-value-references value))]
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
  (let [missing-references? (seq (ctob/find-token-value-references value))
        parsed-value (cft/parse-token-value value)
        out-of-scope (< (:value parsed-value) 0)
        references (seq (ctob/find-token-value-references value))]
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
        references (seq (ctob/find-token-value-references value))]
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
  (let [valid-text-decoration (ctt/valid-text-decoration value)
        references (seq (ctob/find-token-value-references value))]
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
  (let [valid-font-weight (ctt/valid-font-weight-variant value)
        references (seq (ctob/find-token-value-references value))]
    (cond
      valid-font-weight
      {:value value}

      references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      :else
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-font-weight value)]})))

(defn- parse-sd-token-font-family-value
  [value]
  (let [missing-references (seq (some ctob/find-token-value-references value))]
    (cond
      missing-references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      :else
      {:value (-> (js->clj value) (flatten))})))

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
  [value]
  (let [missing-references
        (when (string? value)
          (seq (ctob/find-token-value-references value)))]
    (cond
      missing-references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference missing-references)]
       :references missing-references}

      (string? value)
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-typography value)]}

      :else
      (let [converted (js->clj value :keywordize-keys true)
            valid-typography (reduce
                              (fn [acc [k v]]
                                (let [{:keys [errors value]} (parse-atomic-typography-value k v)]
                                  (if (seq errors)
                                    (update acc :errors concat (map #(assoc % :typography-key k) errors))
                                    (assoc-in acc [:value k] (or value v)))))
                              {:value {}}
                              converted)]
        valid-typography))))

(defn collect-typography-errors [token]
  (group-by :typography-key (:errors token)))

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
