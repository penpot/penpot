(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "src" "vendor")
 {:main 'uxbox-worker.main
  :output-to "resources/public/js/worker.js"
  :output-dir "resources/public/js/worker"
  :asset-path "js"
  :parallel-build false
  :optimizations :simple
  :static-fns true
  :language-in  :ecmascript6
  :language-out :ecmascript5
  :pretty-print true
  :verbose true})
