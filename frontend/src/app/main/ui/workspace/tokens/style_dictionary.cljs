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
                             :format (fn [res]
                                       (.-tokens (.-dictionary res)))})
    sd))

;; Functions -------------------------------------------------------------------

(defn token-self-reference? [token-name reference-string]
  (let [escaped-name (str/replace token-name "." "\\.")
        regex (-> (str "{" escaped-name "}")
                  (re-pattern))]
    (re-find regex reference-string)))

(comment
  (token-self-reference? {:name "some.value"} "{md} + {some.value}")
  (token-self-reference? {:name "some.value"} "some.value")
  (token-self-reference? {:name "some.value"} "{some|value}")
  (token-self-reference? {:name "sm"} "{md} + {lg}")
  (token-self-reference? {:name "sm"} "1")
  (token-self-reference? {:name ""} "121")
  nil)

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
    (js/console.log "sd-tokens" sd-tokens)
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

(def new-token-temp-name
  "TOKEN_STUDIO_SYSTEM.TEMP")

(defn use-debonced-resolve-callback
  [name-ref token tokens callback & {:keys [cached timeout]
                                     :or {cached {}
                                          timeout 500}}]
  (let [timeout-id-ref (mf/use-ref nil)
        cache (mf/use-ref cached)
        debounced-resolver-callback
        (mf/use-callback
         (mf/deps token callback tokens)
         (fn [event]
           (let [input (dom/get-target-val event)
                 timeout-id (js/Symbol)]
             (mf/set-ref-val! timeout-id-ref timeout-id)
             (js/setTimeout
              (fn []
                (when (= (mf/ref-val timeout-id-ref) timeout-id)
                  (let [cached (-> (mf/ref-val cache)
                                   (get tokens))
                        token-name (if (empty? @name-ref) new-token-temp-name @name-ref)]
                    (cond
                      cached (callback cached)
                      (token-self-reference? token-name input) (callback :error/token-self-reference)
                      :else (let [token-id (or (:id token) (random-uuid))
                                  new-tokens (update tokens token-id merge {:id token-id
                                                                            :value input
                                                                            :name token-name})]
                              (-> (resolve-tokens+ new-tokens)
                                  (p/finally
                                    (fn [resolved-tokens _err]
                                      (js/console.log "input" input (empty? (str/trim input)))
                                      (cond
                                        ;; Ignore outdated callbacks because the user input changed since it tried to resolve
                                        (not= (mf/ref-val timeout-id-ref) timeout-id) nil
                                        (empty? (str/trim input)) (callback nil)
                                        :else (let [resolved-token (get resolved-tokens token-id)]
                                                (js/console.log "resolved-token" resolved-token)
                                                (if (:resolved-value resolved-token)
                                                  (do
                                                    (mf/set-ref-val! cache (assoc (mf/ref-val cache) input resolved-tokens))
                                                    (callback resolved-token))
                                                  (callback :error/token-missing-reference))))))))))))


              timeout))))]
    debounced-resolver-callback))

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
