;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.helpers
  "General purpose RPC helpers."
  (:require [app.common.data.macros :as dm]))

(defn http-cache
  [{:keys [max-age]}]
  (fn [_ response]
    (let [exp (if (integer? max-age) max-age (inst-ms max-age))
          val (dm/fmt "max-age=%" (int (/ exp 1000.0)))]
      (update response :headers assoc "cache-control" val))))
