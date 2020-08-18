;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.worker
  (:require
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.worker.impl :as impl]
   [app.worker.selection]
   [app.worker.thumbnails]
   [app.worker.snaps]
   [app.util.object :as obj]
   [app.util.transit :as t]
   [app.util.worker :as w])
  (:import goog.Uri))

;; --- Messages Handling

(s/def ::cmd keyword?)
(s/def ::payload
  (s/keys :req-un [::cmd]))

(s/def ::sender-id uuid?)
(s/def ::message
  (s/keys :req-un [::payload ::sender-id]))

(defn- handle-message
  [{:keys [sender-id payload] :as message}]
  (us/assert ::message message)
  (try
    (let [result (impl/handler payload)]
      (cond
        (p/promise? result)
        (p/handle result
                  (fn [msg]
                    (.postMessage js/self (t/encode
                                           {:reply-to sender-id
                                            :payload msg})))
                  (fn [err]
                    (.postMessage js/self (t/encode
                                           {:reply-to sender-id
                                            :error {:data (ex-data err)
                                                    :message (ex-message err)}}))))

        (or (rx/observable? result)
            (rx/subject? result))
        (throw (ex-info "not implemented" {}))

        :else
        (.postMessage js/self (t/encode
                               {:reply-to sender-id
                                :payload result}))))
    (catch :default e
      (let [message {:reply-to sender-id
                     :error {:data (ex-data e)
                             :message (ex-message e)}}]
        (.postMessage js/self (t/encode message))))))

(defn- on-message
  [event]
  (when (nil? (.-source event))
    (let [message (.-data event)
          message (t/decode message)]
      (handle-message message))))

(.addEventListener js/self "message" on-message)

(defn ^:dev/before-load stop []
  (.removeEventListener js/self "message" on-message))

(defn ^:dev/after-load start []
  []
  (.addEventListener js/self "message" on-message))


