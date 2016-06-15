(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src" "vendor")
   {:main 'uxbox.view
    :parallel-build false
    :warnings {:ns-var-clash false}
    :output-to "dist/view/js/view.js"
    :source-map "dist/view/js/view.js.map"
    :output-dir "dist/view/js/view/"
    :closure-defines {"uxbox.common.constants.url"
                      "https://test.uxbox.io/api"}
    :optimizations :simple
    :externs ["externs/main.js"]
    :source-map "dist/view/js/view.js.map"
    :static-fns true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
