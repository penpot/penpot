(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "vendor")
   {:main 'uxbox.worker
    :output-to "resources/public/js/worker.js"
    :output-dir "resources/public/js/worker"
    :asset-path "js"
    :parallel-build true
    :optimizations :simple
    :static-fns true
    :pretty-print true
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
