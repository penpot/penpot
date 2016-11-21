(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.worker
    :output-to "dist/js/worker.js"
    :source-map "dist/js/worker.js.map"
    :output-dir "dist/js/worker"
    :asset-path "js"
    :parallel-build true
    :optimizations :advanced
    :cache-analysis false
    :static-fns true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
