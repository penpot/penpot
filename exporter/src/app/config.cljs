(ns app.config
  (:require
   ["process" :as process]
   [cljs.pprint]
   [cuerdas.core :as str]))

(defn- keywordize
  [s]
  (-> (str/kebab s)
      (str/keyword)))

(defonce env
  (let [env (unchecked-get process "env")]
    (persistent!
     (reduce #(assoc! %1 (keywordize %2) (unchecked-get env %2))
             (transient {})
             (js/Object.keys env)))))

(defonce config
  {:domain (:app-domain env "localhost:3449")})
