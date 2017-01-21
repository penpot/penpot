{:dev
 {:plugins [[lein-ancient "0.6.10"]]
  :dependencies [[clj-http "2.1.0"]]
  :jvm-opts ["-Xms50m" "-Xmx200m" "-XX:+UseG1GC"]
  :main ^:skip-aot uxbox.main}

 :uberjar
 {:jar-exclusions [#"\.swp|\.swo|user\.clj" #"public/media"]
  :jar-name "uxbox.jar"
  :uberjar-name "uxbox-backend.jar"
  :target-path "dist/"}}
