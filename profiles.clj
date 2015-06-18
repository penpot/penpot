{
 ;; Development dependencies
 :dev
 {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                 [figwheel-sidecar "0.5.0-2" :scope "test"]]

  ;; :plugins [[lein-ancient "0.6.7" :exclusions [org.clojure/tools.reader]]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]

  :env {:config "config.edn"}}

 ;; Common dependencies

 :common
 {:dependencies [[org.clojure/clojure "1.7.0"]
                 [funcool/cats "1.2.0"]]}

 ;; Backend profile

 :back
 [:common
  {:source-paths ["src/backend"]
   :test-paths ["test/backend"]
   :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                  [org.slf4j/slf4j-simple "1.7.12" :scope "provided"]
                  [com.stuartsierra/component "0.3.0"]
                  [funcool/catacumba "0.9.0"]
                  [jarohen/nomad "0.7.2" :exclusions [org.clojure/tools.reader]]
                  [danlentz/clj-uuid "0.1.6"]
                  [com.datomic/datomic-free "0.9.5302"
                   :exclusions [commons-codec joda-time]]
                  [environ "1.0.1"]
                  [aleph "0.4.0"]]}]

 ;; Frontend profile

 :front
 [:common
  {:source-paths ["src/frontend"]
   :test-paths ["test/frontend"]
   :dependencies [[org.clojure/clojurescript "1.7.189"]
                  [funcool/cuerdas "0.7.0"]
                  [rum "0.6.0"]
                  [funcool/promesa "0.6.0"]
                  [funcool/beicon "0.3.0"]
                  [funcool/cats "1.2.0"]
                  [cljsjs/moment "2.10.6-0"]
                  [figwheel-sidecar "0.5.0-2" :scope "test"]
                  [bidi "1.21.0"]
                  [funcool/hodgepodge "0.1.4"]]}]}

   ;; :plugins [[lein-cljsbuild "1.1.0" :exclusions [org.clojure/clojure]]
   ;;           [lein-figwheel "0.3.9" :exclusions [org.clojure/clojurescript
   ;;                                               org.codehaus.plexus/plexus-utils]]
   ;;           [hiccup-bridge "1.0.1" :exclusions [org.clojure/clojure]]]


  ;;  :cljsbuild
  ;;  {:builds [{:id "dev"
  ;;             :source-paths ["src/frontend" "test/frontend"]

  ;;             :figwheel { :on-jsload "uxbox.core/on-js-reload" }

  ;;             :compiler {:main uxbox.core
  ;;                        :asset-path "/js/compiled/out"
  ;;                        :output-to "resources/public/js/compiled/uxbox.js"
  ;;                        :output-dir "resources/public/js/compiled/out"
  ;;                        :source-map-timestamp true
  ;;                        :warnings {:single-segment-namespace false}
  ;;                        :externs ["externs/bacon.js"]
  ;;                        :foreign-libs [{:file "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.js"
  ;;                                        :file-min "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.min.js"
  ;;                                        :provides ["bacon"]}]}}

  ;;            {:id "test"
  ;;             :source-paths ["src" "test"]
  ;;             :notify-command ["node" "out/tests.js"]
  ;;             :compiler {:output-to "out/tests.js"
  ;;                        :output-dir "out"
  ;;                        :source-map true
  ;;                        :static-fns true
  ;;                        :cache-analysis false
  ;;                        :main uxbox.runner
  ;;                        :optimizations :none
  ;;                        :target :nodejs
  ;;                        :pretty-print true
  ;;                        :warnings {:single-segment-namespace false}
  ;;                        :externs ["externs/bacon.js"]
  ;;                        :foreign-libs [{:file "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.js"
  ;;                                        :file-min "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.min.js"
  ;;                                        :provides ["bacon"]}]}}

  ;;            {:id "min"
  ;;             :source-paths ["src"]
  ;;             :compiler {:output-to "resources/public/js/compiled/uxbox.js"
  ;;                        :main uxbox.core
  ;;                        :optimizations :advanced
  ;;                        :pretty-print false
  ;;                        :warnings {:single-segment-namespace false}
  ;;                        :externs ["externs/bacon.js"]
  ;;                        :foreign-libs [{:file "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.js"
  ;;                                        :file-min "http://cdnjs.cloudflare.com/ajax/libs/bacon.js/0.7.73/Bacon.min.js"
  ;;                                        :provides ["bacon"]}]}}]}

  ;;  :figwheel
  ;;  {;; :http-server-root "public" ;; default and assumes "resources"
  ;;   ;; :server-port 3449 ;; default
  ;;   :css-dirs ["resources/public/css"] ;; watch and update CSS

  ;;   ;; Start an nREPL server into the running figwheel process
  ;;   :nrepl-port 7888

  ;;   ;; Server Ring Handler (optional)
  ;;   ;; if you want to embed a ring handler into the figwheel http-kit
  ;;   ;; server, this is for simple ring servers, if this
  ;;   ;; doesn't work for you just run your own server :)
  ;;   :ring-handler uxbox.ring/index

  ;;   ;; To be able to open files in your editor from the heads up display
  ;;   ;; you will need to put a script on your path.
  ;;   ;; that script will have to take a file path and a line number
  ;;   ;; ie. in  ~/bin/myfile-opener
  ;;   ;; #! /bin/sh
  ;;   ;; emacsclient -n +$2 $1
  ;;   ;;
  ;;   ;; :open-file-command "myfile-opener"

  ;;   ;; if you want to disable the REPL
  ;;   ;; :repl false

  ;;   ;; to configure a different figwheel logfile path
  ;;   ;; :server-logfile "tmp/logs/figwheel-logfile.log"
  ;;   }}
  ;; ]}
