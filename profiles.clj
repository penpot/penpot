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
                 [funcool/cats "1.2.1"]]}

 ;; Backend profile

 :back
 [:common
  {:source-paths ["backend"]
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
  {:source-paths ["frontend"]
   :test-paths ["test/frontend"]
   :dependencies [[org.clojure/clojurescript "1.7.189"]
                  [funcool/cuerdas "0.7.0"]
                  [rum "0.6.0" :exclusions [sablono]]
                  [sablono "0.5.3"]
                  [cljsjs/react "0.14.3-0"]
                  [cljsjs/react-dom "0.14.3-1"]
                  [cljsjs/react-dom-server "0.14.3-0"]

                  [bouncer "0.3.3"]
                  [funcool/promesa "0.6.0"]
                  [funcool/beicon "0.5.0"]
                  [cljsjs/moment "2.10.6-0"]
                  [figwheel-sidecar "0.5.0-2" :scope "test"]
                  [bidi "1.21.0"]
                  [funcool/hodgepodge "0.1.4"]]}]}

