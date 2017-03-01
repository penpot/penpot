(require '[cljs.build.api :as b])

(def options
  {:main 'uxbox.tests.main
   :parallel-build false
   :output-to "out/tests.js"
   :source-map true
   :output-dir "out/tests"
   :optimizations :none
   :static-fns true
   :pretty-print true
   :target :nodejs
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :verbose true})

(let [start (System/nanoTime)]
  (println "Building ...")
  (b/build (b/inputs "src" "test") options)
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
