(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :source-paths ["src" "vendor"]
  :test-paths ["test"]

  :profiles {:dev {:source-paths ["dev"]}}

  :dependencies [[org.clojure/clojure "1.9.0-alpha13" :scope "provided"]
                 [org.clojure/clojurescript "1.9.293" :scope "provided"]

                 ;; Build
                 [figwheel-sidecar "0.5.8" :scope "test"]

                 ;; runtime
                 [com.cognitect/transit-cljs "0.8.239"]
                 [rum "0.10.7"]
                 [cljsjs/react "15.3.1-0"]
                 [cljsjs/react-dom "15.3.1-0"]
                 [cljsjs/react-dom-server "15.3.1-0"]
                 [cljsjs/moment "2.10.6-4"]
                 [funcool/struct "1.0.0"]
                 [funcool/lentes "1.2.0"]
                 [funcool/promesa "1.5.0"]
                 [funcool/beicon "2.3.0"]
                 [funcool/cuerdas "1.0.2"]
                 [funcool/bide "1.0.5"]]
  :plugins [[lein-ancient "0.6.10"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




