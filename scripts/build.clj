(require '[cljs.build.api :as b]
         '[cljs.tagged-literals])

(alter-var-root #'cljs.tagged-literals/*cljs-data-readers*
                assoc 'ux/tr (fn [v] `(uxbox.locales/tr ~v)))

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.core
    :output-to "resources/public/js/main.js"
    :output-dir "resources/public/js"
    :parallel-build false
    :optimizations :advanced
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :asset-path "/js"
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
