(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "vendor" "dev")
 {:main 'bench.core
  :output-to "out/bench.js"
  :output-dir "out"
  :parallel-build false
   :optimizations :simple
  :pretty-print true
  :target :nodejs
  :language-in  :ecmascript6
  :language-out :ecmascript5
  :verbose true})
