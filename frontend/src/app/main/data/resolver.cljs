(ns app.main.data.resolver
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary/utils" :as sd-utils]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   ["tinycolor2$default" :as tinycolor]
   [promesa.core :as p]
   [shadow.resource]))

;; Resolving Examples ----------------------------------------------------------

;; Resolving via the tokens tree

(def tokens-tree (clj->js {"foo" {"value" "1px"}
                           "bar" {"baz" {"value" "{foo}"}}}))

(let [value (sd-utils/resolveReferences "round({bar.baz} + 1.2)" tokens-tree)
      new-token (clj->js {:value value})]
  (sd-transforms/checkAndEvaluateMath new-token))
;; => "2px"

;; Resolving via the tokens map

(def tokens-map
  (doto (js/Map.)
    (.set "{foo}" #js {"value" "1px"})
    (.set "{bar.baz}" #js {"value" "{foo}"})))

(let [value (sd-utils/resolveReferences "round({bar.baz} + 1.2)" tokens-map)
      new-token (clj->js {:value value})]
  (sd-transforms/checkAndEvaluateMath new-token))
;; => "2px"

;; Errors ----------------------------------------------------------------------

(def circular-tokens-map
  (doto (js/Map.)
    (.set "{foo}" #js {"value" "{bar}"})
    (.set "{bar}" #js {"value" "{foo}"})))

(sd-utils/resolveReferences "{foo}" circular-tokens-map)
;; => Execution error (Error) at (<cljs repl>:1).
;;    Circular definition cycle for  => {foo}, {bar}, {foo}
;;    :repl/exception!

(sd-utils/resolveReferences "{foo}" {})
;; => Execution error (Error) at (<cljs repl>:1).
;;    tries to reference foo, which is not defined.
;;    :repl/exception!

(def deep-missing-ref-tokens-map
  (doto (js/Map.)
    (.set "{foo}" #js {"value" "{baz}"})
    (.set "{bar}" #js {"value" "{foo}"})
    (.set "{bam}" #js {"value" "{bar}"})))

(try
  (sd-utils/resolveReferences "{bam}" deep-missing-ref-tokens-map)
  (catch js/Error e {:error e
                     :refs (sd-utils/getReferences "{bam}" deep-missing-ref-tokens-map)}))
;; => {:error #object[u Error: tries to reference {baz}, which is not defined.],
;;     :refs #js [#js {:value "{bar}", :ref #js ["bam"]}]}

;; Transitive Colors -----------------------------------------------------------

(def transitive-colors
  (doto (js/Map.)
    (.set "{color.red}" #js {"value" "#f00"})
    (.set "{color.danger}" #js {"value" "{color.red}" "darken" 0.75})
    (.set "{color.error}" #js {"value" "{color.red}" "lighten" 0.75})))

(defn get-references [token colors-map]
  (letfn [(find-refs [token-str path]
            (when-let [color-obj (.get colors-map token-str)]
              (let [value (.-value color-obj)]
                (if (has-reference? value)
                  ;; Recursively find references for value
                  (let [refs (find-refs value (conj path value))]
                    ;; Return current object with its refs
                    #js [#js (merge
                               (js->clj color-obj)
                               {:ref (clj->js (rest (str/split token-str #"[\{\}]")))}
                               {:references refs})])
                  ;; Base case: no more references
                  #js [#js (merge
                             (js->clj color-obj)
                             {:ref (clj->js (rest (str/split token-str #"[\{\}]")))})]))))]
    (find-refs token [])))

(defn has-reference? [val]
  (str/includes? val "{"))

(sd-utils/getReferences "{color.error}" transitive-colors)
;; => #js [#js {:value "{color.red}", :lighten 0.75, :ref #js ["color" "error"]}]

;; Performance Tests -----------------------------------------------------------

(defonce a (atom nil))

(comment
  ;; Load large token set
  (-> (shadow.resource/inline "1k-tokens.json")
      (rx/of)
      (sd/process-json-stream)
      (rx/sub! #(reset! a %)))

  (-> (shadow.resource/inline "tokens.json")
      (rx/of)
      (sd/process-json-stream)
      (rx/sub! #(reset! a %)))
  nil)

(let [mark-id "resolve-tokens"]
  (js/performance.mark (str mark-id "-start"))
  (-> (ctob/get-all-tokens @a)
      (sd/resolve-tokens+)
      (p/then (fn [result]
                (js/performance.mark (str mark-id "-end"))
                (js/performance.measure mark-id
                                        (str mark-id "-start")
                                        (str mark-id "-end"))
                (let [measure (js/performance.getEntriesByName mark-id)]
                  (js/console.log "OLD Resolve tokens duration (ms):"
                                  (.. measure (at 0) -duration)
                                  result))
                (js/performance.clearMarks)
                (js/performance.clearMeasures)))))

(defn get-all-tokens-map [lib]
  (let [tokens-map (js/Map.)]
    (doseq [set (ctob/get-sets lib)
            token (ctob/get-tokens set)]
      (.set tokens-map (str "{" (:name token) "}") #js {"value" (:value token)
                                                        "token" token}))
    tokens-map))

(defn resolve-reference [reference tokens-map]
  (let [value (sd-utils/resolveReferences reference tokens-map)]
    (sd-transforms/checkAndEvaluateMath #js {:value value})))

(defn resolve-tokens-map [lib]
  (let [tokens-map (get-all-tokens-map lib)]
    (reduce
     (fn [acc [k v]]
       (let [token (.-token v)]
         (assoc acc (:name token) (assoc token :resolved-value (resolve-reference (:value token) tokens-map)))))
     {} (.entries tokens-map))))

(let [mark-id "resolve-tokens-direct"]
  (js/performance.mark (str mark-id "-start"))
  (let [result (resolve-tokens-map @a)]
    (js/performance.mark (str mark-id "-end"))
    (js/performance.measure mark-id
                          (str mark-id "-start")
                          (str mark-id "-end"))
    (let [measure (js/performance.getEntriesByName mark-id)]
      (js/console.log "Resolve tokens duration (ms):"
                    (.. measure (at 0) -duration)
                    result))
    (js/performance.clearMarks)
    (js/performance.clearMeasures)))
