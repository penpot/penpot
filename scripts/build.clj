(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "frontend")
   {:main 'uxbox.core
    :output-to "resources/public/js/main.js"
    :output-dir "resources/public/js"
    :optimizations :advanced
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :asset-path "/js"
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
