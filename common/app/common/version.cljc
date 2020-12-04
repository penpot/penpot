(ns app.common.version
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

(defn parse
  [version]
  (if (= version "%version%")
    {:full "develop"
     :base "develop"
     :build 0
     :commit nil}
    (let [[base build commit] (str/split version #"-" 3)]
      {:full version
       :base base
       :build (d/parse-integer build)
       :commit commit})))
