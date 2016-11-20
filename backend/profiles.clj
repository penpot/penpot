{:dev
 {:plugins [[lein-ancient "0.6.10"]]
  :dependencies [[clj-http "2.1.0"]]
  :main ^:skip-aot uxbox.main}

 :prod
 {:jvm-opts ^:replace ["-Xms4g" "-Xmx4g" "-XX:+UseG1GC"
                       "-XX:+AggressiveOpts" "-server"]}}
