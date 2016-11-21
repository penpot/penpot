(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.view
    :parallel-build false
    :output-to "dist/js/view.js"
    :source-map "dist/js/view.js.map"
    :output-dir "dist/js/view/"
    :closure-defines {"uxbox.config.url" "https://test.uxbox.io/api"
                      "uxbox.config.viewurl" "https://test.uxbox.io/view/"}
    :optimizations :simple
    :cache-analysis false
    :externs ["externs/main.js"]
    :static-fns true
    :elide-asserts true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
