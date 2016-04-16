(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "vendor" "test")
   {:main 'uxbox.test-runner
    :output-to "out/tests.js"
    :output-dir "out"
    :parallel-build false
    :optimizations :none
    :pretty-print true
    :target :nodejs
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
