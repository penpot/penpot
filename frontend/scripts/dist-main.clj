(require '[cljs.build.api :as b])
(require '[environ.core :refer [env]])

(def debug?
  (boolean (:uxbox-debug env nil)))

(def demo?
  (boolean (:uxbox-demo env nil)))

(def defines
  {"uxbox.config.url" "/api"
   "uxbox.config.viewurl" "/view/"
   "uxbox.config.isdemo" demo?})

(def options
  {:main 'uxbox.main
   :parallel-build false
   :output-to "dist/js/main.js"
   :source-map "dist/js/main.js.map"
   :output-dir "dist/js/main"
   :closure-defines defines
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :optimizations :advanced
   :cache-analysis false
   :static-fns true
   :elide-asserts true
   :pretty-print debug?
   :verbose true
   :pseudo-names debug?})

(let [start (System/nanoTime)]
  (println "Building ...")
  (b/build (b/inputs "src" "vendor") options)
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
