;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.util
  (:require
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [vertx.util :as vu]
   [uxbox.core :refer [system]]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.dispatcher :as uds]))

(defn raise-not-found-if-nil
  [v]
  (if (nil? v)
    (ex/raise :type :not-found
              :hint "Object doest not exists.")
    v))

(def constantly-nil (constantly nil))

(defn handle-on-context
  [p]
  (->> (vu/current-context system)
       (vu/handle-on-context p)))
