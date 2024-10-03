(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   [app.common.logging :as l]
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.tinycolor :as tinycolor]
   [app.main.ui.workspace.tokens.token :as wtt]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(l/set-level! "app.main.ui.workspace.tokens.style-dictionary" :warn)

(def StyleDictionary
  "Initiates the global StyleDictionary instance with transforms
  from tokens-studio used to parse and resolved token values."
  (do
    (sd-transforms/registerTransforms sd)
    (.registerFormat sd #js {:name "custom/json"
                             :format (fn [^js res]
                                       (.-tokens (.-dictionary res)))})
    sd))

;; Functions -------------------------------------------------------------------

(defn tokens->style-dictionary+
  "Resolves references and math expressions using StyleDictionary.
  Returns a promise with the resolved dictionary."
  [tokens]
  (let [data (cond-> {:tokens tokens
                      :platforms {:json {:transformGroup "tokens-studio"
                                         :files [{:format "custom/json"
                                                  :destination "fake-filename"}]}}
                      :log {:verbosity "silent"
                            :warnings "silent"
                            :errors {:brokenReferences "console"}}
                      :preprocessors ["tokens-studio"]}
               (l/enabled? "app.main.ui.workspace.tokens.style-dictionary" :debug)
               (update :log merge {:verbosity "verbose"
                                   :warnings "warn"}))
        js-data (clj->js data)]
    (l/debug :hint "Input Data" :js/data js-data)
    (sd. js-data)))

(defn resolve-sd-tokens+
  "Resolves references and math expressions using StyleDictionary.
  Returns a promise with the resolved dictionary."
  [tokens]
  (let [performance-start (js/performance.now)
        sd (tokens->style-dictionary+ tokens)]
    (l/debug :hint "StyleDictionary" :js/style-dictionary sd)
    (-> sd
        (.buildAllPlatforms "json")
        (.catch #(l/error :hint "Styledictionary build error" :js/error %))
        (.then (fn [^js resp]
                 (let [performance-end (js/performance.now)
                       duration-ms (- performance-end performance-start)
                       resolved-tokens (.-allTokens resp)]
                   (l/debug :hint (str "Time elapsed" duration-ms "ms") :duration duration-ms)
                   (l/debug :hint "Resolved tokens" :js/tokens resolved-tokens)
                   resolved-tokens))))))

(defn humanize-errors [{:keys [errors value] :as _token}]
  (->> (map (fn [err]
              (case err
                :error.style-dictionary/missing-reference (str "Could not resolve reference token with the name: " value)
                nil))
            errors)
       (str/join "\n")))

(defn resolve-tokens+
  [tokens & {:keys [names-map?] :as config}]
  (let [{:keys [tree ids-map]} (wtt/token-names-tree-id-map tokens)]
    (p/let [sd-tokens (resolve-sd-tokens+ tree)]
      (let [resolved-tokens (reduce
                             (fn [acc ^js cur]
                               (let [{:keys [type] :as origin-token} (if names-map?
                                                                       (get tokens (.. cur -original -name))
                                                                       (get ids-map (uuid (.-uuid (.-id cur)))))
                                     value (.-value cur)
                                     token-or-err (case type
                                                    :color (if-let [tc (tinycolor/valid-color value)]
                                                             {:value value :unit (tinycolor/color-format tc)}
                                                             {:errors [(wte/error-with-value :error.token/invalid-color value)]})
                                                    (or (wtt/parse-token-value value)
                                                        (if-let [references (-> (ctob/find-token-value-references value)
                                                                                (seq))]
                                                          {:errors [(wte/error-with-value :error.style-dictionary/missing-reference references)]
                                                           :references references}
                                                          {:errors [(wte/error-with-value :error.style-dictionary/invalid-token-value value)]})))
                                     output-token (if (:errors token-or-err)
                                                    (merge origin-token token-or-err)
                                                    (assoc origin-token
                                                           :resolved-value (:value token-or-err)
                                                           :unit (:unit token-or-err)))]
                                 (assoc acc (wtt/token-identifier output-token) output-token)))
                             {} sd-tokens)]
        (l/debug :hint "Resolved tokens" :js/tokens resolved-tokens)
        resolved-tokens))))

;; Hooks -----------------------------------------------------------------------

(defonce !tokens-cache (atom nil))

(defonce !theme-tokens-cache (atom nil))

(defn get-cached-tokens [tokens]
  (get @!tokens-cache tokens tokens))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use resolved values directly.

  This hook will return the unresolved tokens as state until they are processed,
  then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom names-map?]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]
    (mf/use-effect
     (mf/deps tokens config)
     (fn []
       (let [cached (get @cache-atom tokens)]
         (cond
           (nil? tokens) (if names-map? {} [])
           ;; The tokens are already processing somewhere
           (p/promise? cached) (-> cached
                                   (p/then #(reset! tokens-state %))
                                   #_(p/catch js/console.error))
           ;; Get the cached entry
           (some? cached) (reset! tokens-state cached)
           ;; No cached entry, start processing
           :else (let [promise+ (resolve-tokens+ tokens config)]
                   (swap! cache-atom assoc tokens promise+)
                   (p/then promise+ (fn [resolved-tokens]
                                      (swap! cache-atom assoc tokens resolved-tokens)
                                      (reset! tokens-state resolved-tokens))))))))
    @tokens-state))

(defn use-resolved-workspace-tokens [& {:as config}]
  (-> (mf/deref refs/workspace-selected-token-set-tokens)
      (use-resolved-tokens config)))

(defn use-active-theme-sets-tokens [& {:as config}]
  (-> (mf/deref refs/workspace-active-theme-sets-tokens)
      (use-resolved-tokens (merge {:cache-atom !theme-tokens-cache
                                   :names-map? true}
                                  config))))
