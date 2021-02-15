;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

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
  {:public-uri (:penpot-public-uri env "http://localhost:3449")})
