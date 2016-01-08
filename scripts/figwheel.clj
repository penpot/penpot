(require '[figwheel-sidecar.repl :as r]
         '[figwheel-sidecar.repl-api :as ra]
         '[cljs.tagged-literals])

(alter-var-root #'cljs.tagged-literals/*cljs-data-readers*
                assoc 'ux/tr (fn [v] `(uxbox.locales/tr ~v)))

(ra/start-figwheel!
  {:figwheel-options {:css-dirs ["resources/public/css"]}
   :build-ids ["dev"]
   :all-builds
   [{:id "dev"
     :figwheel true
     :source-paths ["frontend"]
     :compiler {:main 'uxbox.core
                :asset-path "js"
                :parallel-build true
                :optimizations :none
                :pretty-print true
                :language-in  :ecmascript5
                :language-out :ecmascript5
                :output-to "resources/public/js/main.js"
                :output-dir "resources/public/js"
                :verbose true}}]})

(ra/cljs-repl)
