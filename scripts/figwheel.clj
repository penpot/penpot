(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra])

(ra/start-figwheel!
  {:figwheel-options {:css-dirs ["resources/public/css"]
                      :server-port 3449
                      :server-ip   "0.0.0.0"}
   :build-ids ["main", "preview"]
   :all-builds
   [{:id "main"
     :figwheel {:on-jsload "uxbox.main.ui/init"}
     :source-paths ["src" "vendor"]
     :compiler
     {:main 'uxbox.main
      :asset-path "js"
      :parallel-build true
      :optimizations :none
      :closure-defines {"uxbox.common.constants.url"
                        "https://test.uxbox.io/api"}
      :warnings {:ns-var-clash false}
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js"
      :verbose true}}
    ]})

(ra/cljs-repl "main")
