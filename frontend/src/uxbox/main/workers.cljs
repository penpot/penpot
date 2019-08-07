;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.workers
  "A interface to webworkers exposed functionality."
  (:require [cljs.spec.alpha :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.spec :as us]
            [uxbox.util.workers :as uw]))

(s/def ::width number?)
(s/def ::height number?)
(s/def ::x-axis number?)
(s/def ::y-axis number?)

(s/def ::initialize-alignment-params
  (s/keys :req-un [::width
                   ::height
                   ::x-axis
                   ::y-axis]))

;; This excludes webworker instantiation on nodejs where
;; the tests are run.
(when (not= *target* "nodejs")
  (defonce worker (uw/init "/js/worker.js")))

(defn align-point
  [point]
  (let [message {:cmd :grid-align :point point}]
    (->> (uw/ask! worker message)
         (rx/map :point))))

(defn initialize-alignment
  [params]
  {:pre [(us/valid? ::initialize-alignment-params params)]}
  (let [message (assoc params :cmd :grid-init)]
    (uw/send! worker message)))
