(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   [app.common.data :refer [ordered-map]]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.token :as wtt]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

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
  [tokens {:keys [debug?]}]
  (let [data (cond-> {:tokens tokens
                      :platforms {:json {:transformGroup "tokens-studio"
                                         :files [{:format "custom/json"
                                                  :destination "fake-filename"}]}}
                      :log {:verbosity "silent"
                            :warnings "silent"
                            :errors {:brokenReferences "console"}}
                      :preprocessors ["tokens-studio"]}
               debug? (update :log merge {:verbosity "verbose"
                                          :warnings "warn"}))
        js-data (clj->js data)]
    (when debug?
      (js/console.log "Input Data" js-data))
    (sd. js-data)))

(defn resolve-sd-tokens+
  "Resolves references and math expressions using StyleDictionary.
  Returns a promise with the resolved dictionary."
  [tokens & {:keys [debug?] :as config}]
  (let [performance-start (js/performance.now)
        sd (tokens->style-dictionary+ tokens config)]
    (when debug?
      (js/console.log "StyleDictionary" sd))
    (-> sd
        (.buildAllPlatforms "json")
        (.catch js/console.error)
        (.then (fn [^js resp]
                 (let [performance-end (js/performance.now)
                       duration-ms (- performance-end performance-start)
                       resolved-tokens (.-allTokens resp)]
                   (when debug?
                     (js/console.log "Time elapsed" duration-ms "ms")
                     (js/console.log "Resolved tokens" resolved-tokens))
                   resolved-tokens))))))

(defn humanize-errors [{:keys [errors value] :as _token}]
  (->> (map (fn [err]
              (case err
                :style-dictionary/missing-reference (str "Could not resolve reference token with the name: " value)
                nil))
            errors)
       (str/join "\n")))

(defn missing-reference-error?
  [errors]
  (and (set? errors)
       (get errors :style-dictionary/missing-reference)))

(defn resolve-tokens+
  [tokens & {:keys [names-map? debug?] :as config}]
  (p/let [sd-tokens (-> (wtt/token-names-tree tokens)
                        (resolve-sd-tokens+ config))]
    (let [resolved-tokens (reduce
                           (fn [acc ^js cur]
                             (let [identifier (if names-map?
                                                (.. cur -original -name)
                                                (uuid (.-uuid (.-id cur))))
                                   origin-token (get tokens identifier)
                                   parsed-value (wtt/parse-token-value (.-value cur))
                                   resolved-token (if (not parsed-value)
                                                    (assoc origin-token :errors [:style-dictionary/missing-reference])
                                                    (assoc origin-token
                                                           :resolved-value (:value parsed-value)
                                                           :resolved-unit (:unit parsed-value)))]
                               (assoc acc (wtt/token-identifier resolved-token) resolved-token)))
                           {} sd-tokens)]
      (when debug?
        (js/console.log "Resolved tokens" resolved-tokens))
      resolved-tokens)))

;; Hooks -----------------------------------------------------------------------

(defonce !tokens-cache (atom nil))

(defonce !theme-tokens-cache (atom nil))

(defn get-cached-tokens [tokens]
  (get @!tokens-cache tokens tokens))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use resolved values directly.

  This hook will return the unresolved tokens as state until they are processed,
  then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom _names-map?]
             :or {cache-atom !tokens-cache}
             :as config}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens))]
    (mf/use-effect
     (mf/deps tokens config)
     (fn []
       (let [cached (get @cache-atom tokens)]
         (cond
           ;; The tokens are already processing somewhere
           (p/promise? cached) (p/then cached #(reset! tokens-state %))
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
  (-> (mf/deref refs/get-active-theme-sets-tokens)
      (use-resolved-tokens {:cache-atom !theme-tokens-cache
                            :names-map? true})))
