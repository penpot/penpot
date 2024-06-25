(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   [app.common.data :as d]
   [app.main.refs :as refs]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def StyleDictionary
  "The global StyleDictionary instance used as an external library for now,
  as the package would need webpack to be bundled,
  because shadow-cljs doesn't support some of the more modern bundler features."
  (do
    (sd-transforms/registerTransforms sd)
    (.registerFormat sd #js {:name "custom/json"
                             :format (fn [^js res]
                                       (.-tokens (.-dictionary res)))})
    sd))

;; Functions -------------------------------------------------------------------

(defn find-token-references
  "Finds token reference values in `value-string` and returns a set with all contained namespaces."
  [value-string]
  (some->> (re-seq #"\{([^}]*)\}" value-string)
           (map second)
           (into #{})))

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
  (let [performance-start (js/window.performance.now)
        sd (tokens->style-dictionary+ tokens config)]
    (when debug?
      (js/console.log "StyleDictionary" sd))
    (-> sd
        (.buildAllPlatforms "json")
        (.catch js/console.error)
        (.then (fn [^js resp]
                 (let [performance-end (js/window.performance.now)
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

(defn tokens-name-map [tokens]
  (->> tokens
       (map (fn [[_ x]] [(:name x) x]))
       (into {})))

(defn resolve-tokens+
  [tokens & {:keys [debug?] :as config}]
  (p/let [sd-tokens (-> (tokens-name-map tokens)
                        (doto js/console.log)
                        (resolve-sd-tokens+ config))]
    (let [resolved-tokens (reduce
                           (fn [acc ^js cur]
                             (let [value (.-value cur)
                                   resolved-value (d/parse-integer (.-value cur))
                                   original-value (-> cur .-original .-value)
                                   id (uuid (.-uuid (.-id cur)))
                                   missing-reference? (and (not resolved-value)
                                                           (re-find #"\{" value)
                                                           (= value original-value))]
                               (cond-> (assoc-in acc [id :resolved-value] resolved-value)
                                 missing-reference? (update-in [id :errors] (fnil conj #{}) :style-dictionary/missing-reference))))
                           tokens sd-tokens)]
      (when debug?
        (js/console.log "Resolved tokens" resolved-tokens))
      resolved-tokens)))

(defn resolve-workspace-tokens+
  [& {:keys [debug?] :as config}]
  (when-let [workspace-tokens @refs/workspace-tokens]
    (resolve-tokens+ workspace-tokens)))

;; Hooks -----------------------------------------------------------------------

(defonce !tokens-cache (atom nil))

(defn use-resolved-tokens
  "The StyleDictionary process function is async, so we can't use resolved values directly.

  This hook will return the unresolved tokens as state until they are processed,
  then the state will be updated with the resolved tokens."
  [tokens & {:keys [cache-atom]
             :or {cache-atom !tokens-cache}}]
  (let [tokens-state (mf/use-state (get @cache-atom tokens tokens))]
    (mf/use-effect
     (mf/deps tokens)
     (fn []
       (let [cached (get @cache-atom tokens)]
         (cond
           ;; The tokens are already processing somewhere
           (p/promise? cached) (p/then cached #(reset! tokens-state %))
           ;; Get the cached entry
           (some? cached) (reset! tokens-state cached)
           ;; No cached entry, start processing
           :else (let [promise+ (resolve-tokens+ tokens)]
                   (swap! cache-atom assoc tokens promise+)
                   (p/then promise+ (fn [resolved-tokens]
                                      (swap! cache-atom assoc tokens resolved-tokens)
                                      (reset! tokens-state resolved-tokens))))))))
    @tokens-state))

(defn use-resolved-workspace-tokens [& {:as config}]
  (-> (mf/deref refs/workspace-tokens)
      (use-resolved-tokens config)))

;; Testing ---------------------------------------------------------------------

(comment
  (defonce !output (atom nil))

  (-> (resolve-workspace-tokens+ {:debug? true})
      (p/then #(reset! !output %)))

  (->> @refs/workspace-tokens
       (resolve-tokens+))

  (->
   (clj->js {"a" {:name "a" :value "5"}
             "b" {:name "b" :value "{a} * 2"}})
   (#(resolve-sd-tokens+ % {:debug? true})))

  (let [example (-> (shadow.resource/inline "./data/example-tokens-set.json")
                    (js/JSON.parse)
                    .-core)]
    (resolve-sd-tokens+ example {:debug? true}))

  nil)
