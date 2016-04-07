(require '[cljs.build.api :as b])

(b/build
 (b/inputs "vendor" "dev")
 {:main 'bench.core
  :output-to "out/bench.js"
  :output-dir "out"
  :parallel-build false
  :optimizations :simple
  :language-in  :ecmascript5
  :language-out :ecmascript5
  :target :nodejs
  :verbose true})
