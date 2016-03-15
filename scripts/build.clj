(require '[cljs.build.api :as b]
         '[cljs.tagged-literals])

(alter-var-root #'cljs.tagged-literals/*cljs-data-readers*
                assoc 'ux/tr (fn [v] `(uxbox.locales/tr ~v)))

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "test")
   {:main 'uxbox.test-runner
    :output-to "out/tests.js"
    :output-dir "out"
    :parallel-build false
    :optimizations :none
    :closure-defines {"uxbox.repo.core.url"
                      "https://test.uxbox.io/api"}
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :target :nodejs
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
