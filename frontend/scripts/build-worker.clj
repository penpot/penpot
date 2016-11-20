(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.worker
    :parallel-build false
    :warnings {:ns-var-clash false}
    :output-to "resources/public/js/worker.js"
    :source-map "resources/public/js/worker.js.map"
    :output-dir "resources/public/js/worker"
    :asset-path "js"
    :optimizations :simple
    :static-fns true
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :pretty-print true
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
