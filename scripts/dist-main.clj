(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "vendor")
   {:main 'uxbox.main
    :parallel-build true
    :warnings {:ns-var-clash false}
    :output-to "dist/js/main.js"
    :output-dir "dist/js"
    :closure-defines {"uxbox.common.constants.url"
                      "https://test.uxbox.io/api"}
    :optimizations :simple
    :externs ["externs/main.js"]
    :source-map "dist/js/main.js.map"
    :static-fns true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
