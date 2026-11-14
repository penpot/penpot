;; Write the generated component programs the common tests pin
;; (`composable_gen_test.cljc`) for the `test-wasm` page: the `cg/run`
;; calls whose program is written out in full, as EDN in a `.js` file, the
;; only kind the development server serves there.
;;
;;   clojure -M scripts/test-wasm-programs.clj

(require '[clojure.walk :as walk])
(import '[java.io PushbackReader StringReader])

(def source "../common/test/common_tests/logic/composable_gen_test.cljc")
(def target "resources/public/js/test-wasm/programs.js")

(defn- literal?
  "Whether `form` holds no symbol: a program written out in full."
  [form]
  (not-any? symbol? (tree-seq coll? seq form)))

(let [reader   (PushbackReader. (StringReader. (slurp source)))
      programs (atom [])]
  (binding [*read-eval* false]
    (loop []
      (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} reader)]
        (when-not (= form ::eof)
          (walk/postwalk (fn [x]
                           (when (and (seq? x) (= 'cg/run (first x)) (map? (second x))
                                      (literal? (second x)))
                             (swap! programs conj (second x)))
                           x)
                         form)
          (recur)))))
  (.mkdirs (.getParentFile (java.io.File. target)))
  (spit target (pr-str (vec (distinct @programs))))
  (println (count (distinct @programs)) "programs written to" target))
