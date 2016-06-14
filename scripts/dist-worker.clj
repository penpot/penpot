(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "vendor")
   {:main 'uxbox.worker
    :output-to "dist/js/worker.js"
    :output-dir "dist/js/worker"
    :source-map "dist/js/worker.js.map"
    :asset-path "js"
    :parallel-build true
    :optimizations :simple
    :static-fns true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
