(require '[cljs.build.api :as b]
         '[cljs.tagged-literals])

(alter-var-root #'cljs.tagged-literals/*cljs-data-readers*
                assoc 'ux/tr (fn [v] `(uxbox.locales/tr ~v)))

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.core
    :parallel-build false
    :warnings {:ns-var-clash false}
    :output-to "resources/public/js/main.js"
    :output-dir "resources/public/js"
    :closure-defines {"uxbox.repo.core.url"
                      "https://test.uxbox.io/api"}
    :optimizations :simple
    :externs ["externs/main.js"]
    :source-map "resources/public/js/main.js.map"
    :static-fns true
    :pretty-print true
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
