(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :source-paths ["src" "vendor"]
  :test-paths ["test"]

  :profiles {:dev {:source-paths ["dev"]}}

  :dependencies [[org.clojure/clojure "1.9.0-alpha4" :scope "provided"]
                 [org.clojure/clojurescript "1.9.36" :scope "provided"]

                 ;; Build
                 [figwheel-sidecar "0.5.3-2" :scope "test"]
                 [com.cognitect/transit-clj "0.8.285"]

                 ;; runtime
                 [com.cognitect/transit-cljs "0.8.237"]
                 [rum "0.9.0"]
                 [cljsjs/react "15.1.0-0"]
                 [cljsjs/react-dom "15.1.0-0"]
                 [cljsjs/moment "2.10.6-4"]
                 [funcool/struct "0.1.0"]
                 [funcool/lentes "1.0.1"]
                 [funcool/httpurr "0.6.0"]
                 [funcool/promesa "1.3.1"]
                 [funcool/beicon "2.1.0"]
                 [funcool/cuerdas "0.7.2"]
                 [bidi "2.0.9"]]
  :plugins [[lein-ancient "0.6.10"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




