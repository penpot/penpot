;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.frontend.svgparse
  "A frontend exposed endpoints for svgparse functionality."
  (:require [clojure.spec :as s]
            [promesa.core :as p]
            [catacumba.http :as http]
            [uxbox.util.spec :as us]
            [uxbox.services :as sv]
            [uxbox.util.response :refer (rsp)]
            [uxbox.util.uuid :as uuid]))

(defn parse
  [{body :body :as context}]
  (let [message {:data (slurp body)
                 :type :parse-svg}]
    (->> (sv/query message)
         (p/map #(http/ok (rsp %))))))
