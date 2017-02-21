(require '[cljs.build.api :as b])
(require '[environ.core :refer [env]])

(def debug?
  (boolean (:uxbox-debug env nil)))

(def options
  {:main 'uxbox.worker
   :parallel-build false
   :output-to "dist/js/worker.js"
   :source-map "dist/js/worker.js.map"
   :output-dir "dist/js/worker"
   :externs ["externs/main.js"]
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :optimizations :advanced
   :cache-analysis false
   :static-fns true
   :elide-asserts true
   :pretty-print debug?
   :verbose true
   :pseudo-names debug?
   :compiler-stats true})

(let [start (System/nanoTime)]
  (println "Building ...")
  (b/build (b/inputs "src") options)
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
