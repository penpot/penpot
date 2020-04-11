;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.worker
  (:require [beicon.core :as rx]
            [cuerdas.core :as str]
            [uxbox.util.transit :as t]
            [uxbox.common.uuid :as uuid]
            [uxbox.worker.impl :as impl]
            [uxbox.worker.align]))

(enable-console-print!)

(defonce id (uuid/next))

(defn- on-message
  [event]
  (when (nil? (.-source event))
    (let [message (t/decode (.-data event))]
      (impl/handler message))))

(defonce _
  (.addEventListener js/self "message" on-message))

(println (str/format "Worker with id '%s' is initialized." id))
