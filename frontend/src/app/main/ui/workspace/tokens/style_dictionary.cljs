(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   [app.main.refs :as refs]
   [promesa.core :as p]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def StyleDictionary
  "The global StyleDictionary instance used as an external library for now,
  as the package would need webpack to be bundled,
  because shadow-cljs doesn't support some of the more modern bundler features."
  js/window.StyleDictionary)

(defonce !StyleDictionary (atom nil))

;; Helpers ---------------------------------------------------------------------

(defn tokens->tree
  "Convert flat tokens list into a tokens tree that is consumable by StyleDictionary."
  [tokens]
  (reduce
   (fn [acc [_ {:keys [type name] :as token}]]
     (->> (update token :id str)
          (assoc-in acc [type name])))
   {} tokens))

;; Functions -------------------------------------------------------------------

(defn tokens->style-dictionary+
  "Resolves references and math expressions using StyleDictionary.
  Returns a promise with the resolved dictionary."
  [tokens {:keys [debug?]}]
  (let [data (cond-> {:tokens tokens
                      :platforms {:json {:transformGroup "tokens-studio"
                                         :files [{:format "custom/json"
                                                  :destination "fake-filename"}]}}
                      :preprocessors ["tokens-studio"]}
               debug? (assoc :log {:warnings "warn"
                                   :verbosity "verbose"}))
        js-data (clj->js data)]
    (when debug?
      (js/console.log "Input Data" js-data))
    (StyleDictionary. js-data)))

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

(defn resolve-tokens+
  [tokens & {:keys [debug?] :as config}]
  (p/let [sd-tokens (-> (tokens->tree tokens)
                        (clj->js)
                        (resolve-sd-tokens+ config))]
    (let [resolved-tokens (reduce
                           (fn [acc cur]
                             (let [resolved-value (.-value cur)
                                   id (uuid (.-id cur))]
                               (assoc-in acc [id :value] resolved-value)))
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
  [tokens]
  (let [tokens-state (mf/use-state (get @!tokens-cache tokens tokens))]
    (mf/use-effect
     (mf/deps tokens)
     (fn []
       (let [cached (get @!tokens-cache tokens)]
         (cond
           ;; The tokens are already processing somewhere
           (p/promise? cached) (p/then cached #(reset! tokens-state %))
           ;; Get the cached entry
           (some? cached) (reset! tokens-state cached)
           ;; No cached entry, start processing
           :else (let [promise+ (resolve-tokens+ tokens)]
                   (swap! !tokens-cache assoc tokens promise+)
                   (p/then promise+ (fn [resolved-tokens]
                                      (swap! !tokens-cache assoc tokens resolved-tokens)
                                      (reset! tokens-state resolved-tokens))))))))
    @tokens-state))

;; Testing ---------------------------------------------------------------------

(defn tokens-studio-example []
  (-> (shadow.resource/inline "./data/example-tokens-set.json")
      (js/JSON.parse)
      .-core))

(comment

  (defonce !output (atom nil))

  @!output

  (-> (resolve-workspace-tokens+ {:debug? true})
      (p/then #(reset! !output %)))

  (-> @refs/workspace-tokens
      (tokens->tree)
      (clj->js)
      (#(doto % js/console.log))
      (resolve-sd-tokens+ {:debug? true}))

  (-> (tokens-studio-example)
      (resolve-sd-tokens+ {:debug? true}))

  nil)
