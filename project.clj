(defproject uxbox "0.1.0-SNAPSHOT"
  :description "UXBox UI"
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"]

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.228" :scope "provided"]
                 [figwheel-sidecar "0.5.0-6" :scope "test"]

                 ;; runtime
                 [com.cognitect/transit-cljs "0.8.237"]
                 [rum "0.6.0" :exclusions [sablono]]
                 [sablono "0.6.2"]
                 [cljsjs/react "15.0.0-rc.1-0"]
                 [cljsjs/react-dom "15.0.0-rc.1-0"]
                 [cljsjs/moment "2.10.6-3"]
                 [funcool/lentes "1.0.0"]
                 [funcool/httpurr "0.5.0-20160314.065111-4"]
                 [funcool/promesa "0.8.1"]
                 [funcool/beicon "1.0.3"]
                 [funcool/cuerdas "0.7.1"]
                 [funcool/hodgepodge "0.1.4"]
                 [bouncer "1.0.0"]
                 [bidi "1.25.1"]]
  :plugins [[lein-ancient "0.6.7"]]
  :clean-targets ^{:protect false} ["resources/public/js" "target"]
  )




