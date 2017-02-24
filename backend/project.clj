(defproject uxbox-backend "0.1.0-SNAPSHOT"
  :description "UXBox backend."
  :url "http://uxbox.github.io"
  :license {:name "MPL 2.0" :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :source-paths ["src" "vendor"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  ;; :jvm-opts ["-Dcom.sun.management.jmxremote.port=9090"
  ;;            "-Dcom.sun.management.jmxremote.authenticate=false"
  ;;            "-Dcom.sun.management.jmxremote.ssl=false"
  ;;            "-Dcom.sun.management.jmxremote.rmi.port=9090"
  ;;            "-Djava.rmi.server.hostname=0.0.0.0"]
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/tools.logging "0.3.1"]
                 [funcool/suricatta "1.3.1"]
                 [funcool/promesa "1.8.0"]
                 [funcool/catacumba "2.0.0"]
                 [funcool/cuerdas "2.0.3"]
                 [funcool/datoteka "1.0.0"]

                 [org.clojure/data.xml "0.1.0-beta2"]
                 [org.jsoup/jsoup "1.10.2"]

                 [hiccup "1.0.5"]
                 [org.im4java/im4java "1.4.0"]

                 [org.slf4j/slf4j-simple "1.7.23"]
                 [com.layerware/hugsql-core "0.4.7"
                  :exclusions [org.clojure/tools.reader]]
                 [niwinz/migrante "0.1.0"]


                 [buddy/buddy-sign "1.4.0"]
                 [buddy/buddy-hashers "1.2.0"]

                 [org.xerial.snappy/snappy-java "1.1.2.6"]
                 [com.github.spullara.mustache.java/compiler "0.9.4"]
                 [org.postgresql/postgresql "42.0.0"]
                 [org.quartz-scheduler/quartz "2.2.3"]
                 [org.quartz-scheduler/quartz-jobs "2.2.3"]
                 [commons-io/commons-io "2.5"]
                 [com.draines/postal "2.0.2" :exclusions [commons-codec]]

                 [hikari-cp "1.7.5"]
                 [mount "0.1.11"]
                 [environ "1.1.0"]])
