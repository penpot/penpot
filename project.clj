(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox client"
  :url "http://uxbox.github.io"
  :license {:name "" :url ""}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.228" :scope "provided"]
                 [figwheel-sidecar "0.5.0-2" :scope "test"]

                 ;; runtime
                 [rum "0.6.0" :exclusions [sablono]]
                 [sablono "0.5.3"]
                 [cljsjs/react "0.14.3-0"]
                 [cljsjs/react-dom "0.14.3-1"]
                 [cljsjs/react-dom-server "0.14.3-0"]
                 [cljsjs/moment "2.10.6-0"]
                 [funcool/promesa "0.7.0"]
                 [funcool/beicon "0.5.1"]
                 [funcool/cats "1.2.1"]
                 [funcool/cuerdas "0.7.1"]
                 [funcool/hodgepodge "0.1.4"]
                 [bouncer "1.0.0"]
                 [bidi "1.21.0"]]

  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




