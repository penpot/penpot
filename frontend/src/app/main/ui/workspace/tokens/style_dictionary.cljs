(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.tinycolor :as tinycolor]
   [app.main.ui.workspace.tokens.token :as wtt]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(l/set-level! "app.main.ui.workspace.tokens.style-dictionary" :warn)

;; === Style Dictionary

(def setup-style-dictionary
  "Initiates the StyleDictionary instance.
  Setup transforms from tokens-studio used to parse and resolved token values."
  (do
    (sd-transforms/registerTransforms sd)
    (.registerFormat sd #js {:name "custom/json"
                             :format (fn [^js res]
                                       (.-tokens (.-dictionary res)))})
    sd))

(def default-config
  {:platforms {:json
               {:transformGroup "tokens-studio"
                ;; Required: The StyleDictionary API is focused on files even when working in the browser
                :files [{:format "custom/json" :destination "penpot"}]}}
   :preprocessors ["tokens-studio"]
   ;; Silences style dictionary logs and errors
   ;; We handle token errors in the UI
   :log {:verbosity "silent"
         :warnings "silent"
         :errors {:brokenReferences "console"}}})

(defn parse-sd-token-color-value
  "Parses `value` of a color `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the value is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (if-let [tc (tinycolor/valid-color value)]
    {:value value :unit (tinycolor/color-format tc)}
    {:errors [(wte/error-with-value :error.token/invalid-color value)]}))

(defn parse-sd-token-dimensions-value
  "Parses `value` of a dimensions `sd-token` into a map like `{:value 1 :unit \"px\"}`.
  If the `value` is not parseable and/or has missing references returns a map with `:errors`."
  [value]
  (or
   (wtt/parse-token-value value)
   (if-let [references (seq (ctob/find-token-value-references value))]
     {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
      :references references}
     {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))

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
           parsed-token-value (case (:type origin-token)
                                :color (parse-sd-token-color-value value)
                                (parse-sd-token-dimensions-value value))
           output-token (if (:errors parsed-token-value)
                          (merge origin-token parsed-token-value)
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
    (-> (sd. (clj->js config))
        (.buildAllPlatforms "json")
        (p/then #(.-allTokens ^js %)))))

(defn resolve-tokens-tree+
  ([tokens-tree get-token]
   (resolve-tokens-tree+ tokens-tree get-token (StyleDictionary. default-config)))
  ([tokens-tree get-token style-dictionary]
   (-> style-dictionary
       (add-tokens tokens-tree)
       (build-dictionary)
       (p/then #(process-sd-tokens % get-token)))))

(defn sd-token-name [^js sd-token]
  (.. sd-token -original -name))

(defn sd-token-uuid [^js sd-token]
  (uuid (.-uuid (.-id ^js sd-token))))

(defn resolve-tokens+ [tokens]
  (resolve-tokens-tree+ (ctob/tokens-tree tokens) #(get tokens (sd-token-name %))))

(defn resolve-tokens-interactive+
  "Interactive check of resolving tokens.
  Uses a ids map to backtrace the original token from the resolved StyleDictionary token.

  We have to pass in all tokens from all sets in the entire library to style dictionary
  so we know if references are missing / to resolve them and possibly show interactive previews (in the tokens form) to the user.

  Since we're using the :name path as the identifier we might be throwing away or overriding tokens in the tree that we pass to StyleDictionary.

  So to get back the original token from the resolved sd-token (see my updates for what an sd-token is) we include a temporary :id for the token that we pass to StyleDictionary,
  this way after the resolving computation we can restore any token, even clashing ones with the same :name path by just looking up that :id in the ids map."
  [tokens]
  (let [{:keys [tokens-tree ids]} (ctob/backtrace-tokens-tree tokens)]
    (resolve-tokens-tree+ tokens-tree  #(get ids (sd-token-uuid %)))))

(defn resolve-tokens-with-errors+ [tokens]
  (resolve-tokens-tree+
   (ctob/tokens-tree tokens)
   #(get tokens (sd-token-name %))
   (StyleDictionary. (assoc default-config :log {:verbosity "verbose"}))))

;; === Import

(defn reference-errors
  "Extracts reference errors from StyleDictionary."
  [err]
  (let [[header-1 header-2 & errors] (str/split err "\n")]
    (when (and
          ;; TODO: This needs translations
           (= header-1 "Error: ")
           (= header-2 "Reference Errors:"))
      errors)))

(defn process-json-stream [data-stream]
  (->> data-stream
       (rx/map (fn [data]
                 (try
                   (t/decode-str data)
                   (catch js/Error e
                     (throw (wte/error-ex-info :error.import/json-parse-error data e))))))
       (rx/map (fn [json-data]
                 (try
                   (ctob/decode-dtcg-json (ctob/ensure-tokens-lib nil) json-data)
                   (catch js/Error e
                     (throw (wte/error-ex-info :error.import/invalid-json-data json-data e))))))
       (rx/mapcat (fn [tokens-lib]
                    (try
                      (-> (ctob/get-all-tokens tokens-lib)
                          (resolve-tokens-with-errors+)
                          (p/then (fn [_] tokens-lib))
                          (p/catch (fn [sd-error]
                                     (let [reference-errors (reference-errors sd-error)
                                           err (if reference-errors
                                                 (wte/error-ex-info :error.import/style-dictionary-reference-errors reference-errors sd-error)
                                                 (wte/error-ex-info :error.import/style-dictionary-unknown-error sd-error sd-error))]
                                       (throw err)))))
                      (catch js/Error e
                        (p/rejected (wte/error-ex-info :error.import/style-dictionary-unknown-error "" e))))))))

;; === Errors

(defn humanize-errors [{:keys [errors] :as token}]
  (->> (map (fn [err]
              (case (:error/code err)
                ;; TODO: This needs translations
                :error.style-dictionary/missing-reference (str "Could not resolve reference token with the name: " (:error/value err))
                nil))
            errors)
       (str/join "\n")))

;; === Hooks

(defonce !tokens-cache (atom nil))

(defonce !theme-tokens-cache (atom nil))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use resolved values directly.

  This hook will return the unresolved tokens as state until they are processed,
  then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom interactive?]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]
    (mf/use-effect
     (mf/deps tokens config)
     (fn []
       (let [cached (get @cache-atom tokens)]
         (cond
           (nil? tokens) nil
           ;; The tokens are already processing somewhere
           (p/promise? cached) (-> cached
                                   (p/then #(reset! tokens-state %))
                                   #_(p/catch js/console.error))
           ;; Get the cached entry
           (some? cached) (reset! tokens-state cached)
           ;; No cached entry, start processing
           :else (let [promise+ (if interactive?
                                  (resolve-tokens-interactive+ tokens)
                                  (resolve-tokens+ tokens))]
                   (swap! cache-atom assoc tokens promise+)
                   (p/then promise+ (fn [resolved-tokens]
                                      (swap! cache-atom assoc tokens resolved-tokens)
                                      (reset! tokens-state resolved-tokens))))))))
    @tokens-state))

(defn use-resolved-workspace-tokens []
  (let [active-theme-tokens (mf/deref refs/workspace-active-theme-sets-tokens)
        selected-token-set-tokens (mf/deref refs/workspace-selected-token-set-tokens)
        prefer-selected-token-set-tokens (merge active-theme-tokens selected-token-set-tokens)]
    (use-resolved-tokens prefer-selected-token-set-tokens)))

(defn use-active-theme-sets-tokens []
  (-> (mf/deref refs/workspace-active-theme-sets-tokens)
      (use-resolved-tokens {:cache-atom !theme-tokens-cache})))
