(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "src")
 {:main 'uxbox.worker
  :parallel-build false
  :warnings {:ns-var-clash false}
  :output-to "resources/public/js/worker.js"
  :source-map "resources/public/js/worker.js.map"
  :output-dir "resources/public/js/worker"
  :asset-path "js"
  :optimizations :simple
  :static-fns true
  :language-in  :ecmascript6
  :language-out :ecmascript5
  :pretty-print true
  :verbose true})
