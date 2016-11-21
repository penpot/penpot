(require '[cljs.build.api :as b])

(b/watch
 (b/inputs "src")
 {:main 'uxbox.main
  :parallel-build false
  :output-to "resources/public/js/main.js"
  :output-dir "resources/public/js/main"
  :closure-defines {"uxbox.config.url" "https://test.uxbox.io/api"
                    "uxbox.config.viewurl" "https://test.uxbox.io/view/"}
  :optimizations :advanced
  :externs ["externs/main.js"]
  :static-fns true
  :elide-asserts true
  :pretty-print false
  :language-in  :ecmascript6
  :language-out :ecmascript5
  :verbose true})
