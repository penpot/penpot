(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra])

(ra/start-figwheel!
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      :server-port 3449
                      :server-ip   "0.0.0.0"}
   :build-ids ["main", "preview"]
   :all-builds
   [{:id "main"
     :figwheel {:on-jsload "uxbox.ui/init"}
     :source-paths ["src" "vendor"]
     :compiler
     {:main 'uxbox.main
      :asset-path "js"
      :parallel-build false
      :optimizations :none
      ;; :closure-defines {"uxbox.repo.core.url"
      ;;                   "https://test.uxbox.io/api"}
      :closure-defines {"uxbox.repo.core.url"
                        "http://localhost:6060/api"}
      :warnings {:ns-var-clash false}
      :pretty-print true
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js"
      :verbose true}}

    #_{:id "preview"
     ;; :figwheel {:on-jsload "uxbox.ui/init"}
     :source-paths ["src" "vendor"]
     :compiler
     {:main 'uxbox.core
      :asset-path "js"
      :parallel-build false
      :optimizations :none
      ;; :closure-defines {"uxbox.repo.core.url"
      ;;                   "https://test.uxbox.io/api"}
      :closure-defines {"uxbox.repo.core.url"
                        "http://localhost:6060/api"}
      :warnings {:ns-var-clash false}
      :pretty-print true
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/preview/js/main.js"
      :output-dir "resources/public/preview/js"
      :verbose true}}


]})

(ra/cljs-repl "main")
