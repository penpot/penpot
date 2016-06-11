(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "dev" "vendor")
 {:main 'bench.core
  :output-to "out/bench.js"
  :output-dir "out"
  :parallel-build false
  :optimizations :none
  :static-fns false
  :target :nodejs
  :language-in  :ecmascript6
  :language-out :ecmascript5
  :pretty-print true
  :verbose true})
