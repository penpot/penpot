(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra])

(ra/start-figwheel!
  {:figwheel-options {:css-dirs ["resources/public/css"
                                 "resources/public/view/css"]
                      :server-port 3449
                      :server-ip   "0.0.0.0"}
   :build-ids ["main", "view"]
   :all-builds
   [{:id "main"
     :figwheel {:on-jsload "uxbox.main.ui/init"}
     :source-paths ["src"]
     :compiler
     {:main 'uxbox.main
      :parallel-build false
      :optimizations :none
      :closure-defines {"uxbox.common.constants.url"
                        "https://test.uxbox.io/api"}
      :warnings {:ns-var-clash false}
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js/main"
      :asset-path "js/main"
      :verbose true}}

    {:id "view"
     :figwheel {:on-jsload "uxbox.view.ui/init"}
     :source-paths ["src"]
     :compiler
     {:main 'uxbox.view
      :parallel-build false
      :optimizations :none
      :closure-defines {"uxbox.common.constants.url"
                        "https://test.uxbox.io/api"}
      :warnings {:ns-var-clash false}
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/view/js/view.js"
      :output-dir "resources/public/view/js/view"
      :asset-path "js/view"
      :verbose true}}
    ]})

(ra/cljs-repl "main")
