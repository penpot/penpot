(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :source-paths ["src" "vendor"]
  :test-paths ["test"]

  :profiles {:dev {:source-paths ["dev"]}}

  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [org.clojure/clojurescript "1.9.495" :scope "provided"]

                 ;; Build
                 [figwheel-sidecar "0.5.9" :scope "provided"]
                 [environ "1.1.0"]

                 ;; Runtime
                 [com.cognitect/transit-cljs "0.8.239"]

                 [funcool/rumext "1.0.0"]
                 [rum "0.10.8" :exclusions [cljsjs/react cljsjs/react-dom]]
                 [cljsjs/react "15.5.0-0"]
                 [cljsjs/react-dom "15.5.0-0"]
                 [cljsjs/react-dom-server "15.5.0-0"]

                 [funcool/beicon "3.2.0"]
                 [funcool/bide "1.4.0"]
                 [funcool/cuerdas "2.0.3"]
                 [funcool/lentes "1.2.0"]
                 [funcool/potok "2.1.0"]]
  :plugins [[lein-ancient "0.6.10"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




