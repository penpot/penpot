(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :source-paths ["src" "vendor"]
  :test-paths ["test"]

  :profiles {:dev {:source-paths ["dev"]}}

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.8.40" :scope "provided"]
                 [figwheel-sidecar "0.5.2" :scope "test"]

                 ;; runtime
                 [com.cognitect/transit-cljs "0.8.237"]
                 [rum "0.7.0" :exclusions [sablono]]
                 [sablono "0.7.0"]
                 [cljsjs/react "15.0.1-0"]
                 [cljsjs/react-dom "15.0.1-0"]
                 [cljsjs/moment "2.10.6-4"]
                 [funcool/struct "0.1.0-SNAPSHOT"]
                 [funcool/lentes "1.0.1"]
                 [funcool/httpurr "0.6.0-SNAPSHOT"]
                 [funcool/promesa "1.1.1"]
                 [funcool/beicon "1.2.0"]
                 [funcool/cuerdas "0.7.2"]
                 [bidi "2.0.6"]]
  :plugins [[lein-ancient "0.6.7"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




