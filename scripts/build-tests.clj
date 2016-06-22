(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "test")
   {:main 'uxbox.tests.main
    :parallel-build false
    :warnings {:ns-var-clash false}
    :output-to "out/tests.js"
    :source-map "out/tests.js.map"
    :output-dir "out/tests"
    :optimizations :simple
    :static-fns true
    :pretty-print true
    :target :nodejs
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
