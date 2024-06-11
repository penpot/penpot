(ns app.main.ui.workspace.tokens.style-dictionary
  (:require
   [app.main.refs :as refs]
   [shadow.resource]))

(def StyleDictionary
  "The global StyleDictionary instance used as an external library for now,
  as the package would need webpack to be bundled,
  because shadow-cljs doesn't support some of the more modern bundler features."
  js/window.StyleDictionary)

;; Helpers ---------------------------------------------------------------------

(defn tokens->tree
  "Convert flat tokens list into a tokens tree that is consumable by StyleDictionary."
  [tokens]
  (reduce
   (fn [acc [_ {:keys [type name] :as token}]]
     (assoc-in acc [type name] token))
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

(defn resolve-tokens+
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

;; Testing ---------------------------------------------------------------------

(defn tokens-studio-example []
  (-> (shadow.resource/inline "./data/example-tokens-set.json")
      (js/JSON.parse)
      .-core))

(comment
  (-> @refs/workspace-tokens
      (tokens->tree)
      (clj->js)
      (#(doto % js/console.log))
      (resolve-tokens+ {:debug? true}))

  (-> (tokens-studio-example)
      (resolve-tokens+ {:debug? true}))

  nil)
