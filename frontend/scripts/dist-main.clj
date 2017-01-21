(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "src")
   {:main 'uxbox.main
    :parallel-build false
    :output-to "dist/js/main.js"
    :source-map "dist/js/main.js.map"
    :output-dir "dist/js/main"
    :closure-defines {"uxbox.config.url" "/api"
                      "uxbox.config.viewurl" "/view/"}
    :optimizations :advanced
    :cache-analysis false
    :externs ["externs/main.js"]
    :static-fns true
    :elide-asserts true
    :pretty-print false
    :language-in  :ecmascript6
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
