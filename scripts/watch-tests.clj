(require '[cljs.build.api :as b])

(b/watch (b/inputs "src" "vendor" "test")
  {:main 'uxbox.test-runner
   :output-to "out/tests.js"
   :output-dir "out"
   :parallel-build false
   :optimizations :none
   :pretty-print true
   :target :nodejs
   :language-in  :ecmascript6
   :language-out :ecmascript5
   :verbose true})
