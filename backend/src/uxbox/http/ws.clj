;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http.ws
  "Web Socket handlers"
  (:require
   [uxbox.services.notifications :as nf]
   [vertx.web.websockets :as ws]))

(defn handler
  [{:keys [user] :as req}]
  (ws/websocket
   {:handler #(nf/websocket req %)
    :input-buffer-size 64
    :output-buffer-size 64}))
