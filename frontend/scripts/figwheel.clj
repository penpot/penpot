(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra])

(def options
  {;;"uxbox.config.url" "http://127.0.0.1:6060/api"
   "uxbox.config.url" "https://test.uxbox.io/api"
   })

(ra/start-figwheel!
  {:figwheel-options {:css-dirs ["resources/public/css"
                                 "resources/public/view/css"]
                      :validate-config false
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
      :closure-defines options
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/js/main.js"
      :output-dir "resources/public/js/main"
      :asset-path "/js/main"
      :verbose true}}

    {:id "view"
     :figwheel {:on-jsload "uxbox.view.ui/init"}
     :source-paths ["src"]
     :compiler
     {:main 'uxbox.view
      :parallel-build false
      :optimizations :none
      :closure-defines options
      :language-in  :ecmascript6
      :language-out :ecmascript5
      :output-to "resources/public/js/view.js"
      :output-dir "resources/public/js/view"
      :asset-path "/js/view"
      :verbose true}}
    ]})

(ra/cljs-repl "main")
