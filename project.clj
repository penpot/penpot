(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :source-paths ["src" "vendor"]
  :test-paths ["test"]

  :profiles {:dev {:source-paths ["dev"]}}

  :dependencies [[org.clojure/clojure "1.9.0-alpha10" :scope "provided"]
                 [org.clojure/clojurescript "1.9.89" :scope "provided"]

                 ;; Build
                 [figwheel-sidecar "0.5.4-7" :scope "test"]

                 ;; runtime
                 [com.cognitect/transit-cljs "0.8.239"]
                 [rum "0.10.5"]
                 [cljsjs/react "15.2.1-1"]
                 [cljsjs/react-dom "15.2.1-1"]
                 [cljsjs/react-dom-server "15.2.1-1"]
                 [cljsjs/moment "2.10.6-4"]
                 [funcool/struct "1.0.0"]
                 [funcool/lentes "1.1.0"]
                 [funcool/httpurr "0.6.1"]
                 [funcool/promesa "1.4.0"]
                 [funcool/beicon "2.2.0"]
                 [funcool/cuerdas "0.8.0"]
                 [bidi "2.0.9"]]
  :plugins [[lein-ancient "0.6.10"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




