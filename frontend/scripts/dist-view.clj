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
  {:main 'uxbox.view
   :parallel-build false
   :output-to "dist/js/view.js"
   :source-map "dist/js/view.js.map"
   :output-dir "dist/js/view"
   :externs ["externs/main.js"]
   :closure-defines defines
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
