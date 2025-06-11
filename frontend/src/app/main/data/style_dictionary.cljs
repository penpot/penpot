;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["@tokens-studio/unit-calculator" :as unit-calculator]
   ["style-dictionary$default" :as sd]
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [app.util.array :as array]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))


(defn make-config [base-font-size]
  (unit-calculator/presets.penpot #js {:baseSize base-font-size}))

(def memo-make-config (memoize make-config))

(defn calc [expr base-font-size]
  (try
    (when-let [[result] (-> (unit-calculator/run expr (memo-make-config base-font-size))
                            (.exec))]
      (str (.-value result) (.-unit result)))
    (catch js/Error e
      (let [data (cond
                   (instance? unit-calculator/errors.IncompatibleUnitsError e) (.-data e))]
        (js/console.log e data)))))

(l/set-level! :debug)

;; === Style Dictionary

(defn set-base-font-size [cfg base-font-size]
  (assoc-in cfg [:platforms :json :baseFontSize] base-font-size))

(defn get-base-font-size [^js platform-config]
  (.-baseFontSize platform-config))

(defn check-and-evaluate-math [^js token ^js platform-config]
  (calc (.-value token) (get-base-font-size platform-config)))

(def wrapped-math-transform
  "A wrapper around `sd-transforms/checkAndEvaluateMath` with custom rules for which math expressions to allow:
  - Allows rem/px addition & subtraction by converting rems to px
  - Disallows multiplication or division for different units (e.g.: 1px * 1rem)"
  #js {:type "value"
       :transitive true
       :name "penpot/math-transform"
       :filter #(not= (.-type ^js %) "color")
       :transform check-and-evaluate-math})

(def penpot-transform-group
  #js {:name "penpot"
       :transforms (->> (sd-transforms/getTransforms #js {:platform "none"})
                        (array/map #(if (= % "ts/resolveMath") (.-name wrapped-math-transform) %)))})

(def setup-style-dictionary
  "Initiates the StyleDictionary instance.
  Setup transforms from tokens-studio used to parse and resolved token values."
  (do
    (sd-transforms/register sd)
    (.registerFormat sd #js {:name "custom/json"
                             :format (fn [^js res]
                                       (.-tokens (.-dictionary res)))})
    (.registerTransform sd wrapped-math-transform)
    (.registerTransformGroup sd penpot-transform-group)
    sd))

(def default-config
  (cond-> {:platforms {:json
                       {:transformGroup "tokens-studio"
                        ;; Required: The StyleDictionary API is focused on files even when working in the browser
                        :files [{:format "custom/json" :destination "penpot"}]}}
           :preprocessors ["tokens-studio"]
           ;; Silences style dictionary logs and errors
           ;; We handle token errors in the UI
           :log {:verbosity "silent"
                 :warnings "silent"
                 :errors {:brokenReferences "console"}}}
    (contains? cf/flags :token-units)
    (assoc-in [:platforms :json :transformGroup] (.-name penpot-transform-group))))

(def form-config
  (-> default-config
      (assoc-in [:platforms :json :options :has-error-on-unitless-dimension] true)))

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

;; TODO: After mergin "dimension-tokens" revisit this function to check if it's still
(defn- parse-sd-token-number-value
  "Parses `value` of a number `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (let [number? (or (number? value)
                    (numeric-string? value))
        parsed-value  (if (contains? cf/flags :token-units)
                        (cft/parse-token-value-with-rem value)
                        (cft/parse-token-value value))
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
  (let [parsed-value  (if (contains? cf/flags :token-units)
                        (cft/parse-token-value-with-rem value)
                        (cft/parse-token-value value))
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
  [value has-references?]

  (let [parsed-value (cft/parse-token-value value)
        out-of-scope (not (<= 0 (:value parsed-value) 1))
        references (seq (ctob/find-token-value-references value))]
    (cond (and parsed-value (not out-of-scope))
          parsed-value

          references
          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
           :references references}

          (and (not has-references?) out-of-scope)
          {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-opacity value)]}

          (and has-references? out-of-scope parsed-value)
          (assoc parsed-value :warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-opacity value)])

          :else {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))


(defn- parse-sd-token-stroke-width-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`.
  If the `value` is parseable but is out of range returns a map with `warnings`."
  [value has-references?]
  (let [parsed-value (cft/parse-token-value value)
        out-of-scope (< (:value parsed-value) 0)
        references (seq (ctob/find-token-value-references value))]
    (cond
      (and parsed-value (not out-of-scope))
      parsed-value

      references
      {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
       :references references}

      (and (not has-references?) out-of-scope)
      {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value-stroke-width value)]}

      (and has-references? out-of-scope parsed-value)
      (assoc parsed-value :warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-stroke-width value)])

      :else {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))

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
           has-references? (str/includes? (:value origin-token) "{")
           parsed-token-value (case (:type origin-token)
                                :color (parse-sd-token-color-value value)
                                :opacity (parse-sd-token-opacity-value value has-references?)
                                :stroke-width (parse-sd-token-stroke-width-value value has-references?)
                                :number (parse-sd-token-number-value value)
                                (parse-sd-token-general-value value))
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
                                     :unit (:unit parsed-token-value)))
           sd-errors (.-errors sd-token)
           sd-warnings (.-warnings sd-token)
           output-token (cond-> output-token
                          sd-errors (update :errors d/concat-vec sd-errors)
                          sd-warnings (update :warnings d/concat-vec sd-warnings))]
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
  [tokens & {:keys [sd-config base-font-size]}]
  (let [tokens-tree (ctob/tokens-tree tokens)
        sd (-> (or sd-config default-config)
               (set-base-font-size base-font-size)
               (StyleDictionary.))]
    (resolve-tokens-tree tokens-tree #(get tokens (sd-token-name %)) sd)))

(defn resolve-tokens-interactive
  "Interactive check of resolving tokens.
  Uses a ids map to backtrace the original token from the resolved StyleDictionary token.

  We have to pass in all tokens from all sets in the entire library to style dictionary
  so we know if references are missing / to resolve them and possibly show interactive previews (in the tokens form) to the user.

  Since we're using the :name path as the identifier we might be throwing away or overriding tokens in the tree that we pass to StyleDictionary.

  So to get back the original token from the resolved sd-token (see my updates for what an sd-token is) we include a temporary :id for the token that we pass to StyleDictionary,
  this way after the resolving computation we can restore any token, even clashing ones with the same :name path by just looking up that :id in the ids map."
  [tokens & {:keys [sd-config base-font-size]}]
  (let [{:keys [tokens-tree ids]} (ctob/backtrace-tokens-tree tokens)
        sd (-> (or sd-config default-config)
               (set-base-font-size base-font-size)
               (StyleDictionary.))]
    (resolve-tokens-tree tokens-tree #(get ids (sd-token-uuid %)) sd)))

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
  [tokens & {:keys [cache-atom interactive? base-font-size]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]

    ;; FIXME: this with effect with trigger all the time because
    ;; `config` will be always a different instance

    (mf/with-effect [tokens config base-font-size]
      (let [cached (get @cache-atom tokens)]
        (cond
          (nil? tokens) nil
          ;; The tokens are already processing somewhere
          (rx/observable? cached) (rx/sub! cached #(reset! tokens-state %))
          ;; Get the cached entry
          (some? cached) (reset! tokens-state cached)
          ;; No cached entry, start processing
          :else (let [resolved-tokens-s (if interactive?
                                          (resolve-tokens-interactive tokens {:base-font-size base-font-size})
                                          (resolve-tokens tokens {:base-font-size base-font-size}))]
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
  [tokens & {:keys [interactive? base-font-size]}]
  (let [state* (mf/use-state tokens)]
    (mf/with-effect [tokens interactive? base-font-size]
      (if (seq tokens)
        (let [tpoint  (dt/tpoint-ms)
              tokens-s  (if interactive?
                          (resolve-tokens-interactive tokens {:base-font-size base-font-size})
                          (resolve-tokens tokens {:base-font-size base-font-size}))]

          (-> tokens-s
              (rx/sub! (fn [resolved-tokens]
                         (let [elapsed (tpoint)]
                           (l/dbg :hint "use-resolved-tokens*" :elapsed elapsed)
                           (reset! state* resolved-tokens))))))
        (reset! state* tokens)))
    @state*))
