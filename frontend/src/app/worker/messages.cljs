;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.messages
  "A lightweight layer on top of webworkers api."
  (:require
   [app.common.data :as d]
   [app.common.transit :as t]
   [app.util.object :as obj]))

(defn encode [{:keys [sender-id reply-to payload buffer?] :as message}]
  #js {:cmd (d/name (:cmd payload))
       :senderId (when sender-id (str sender-id))
       :replyTo (when reply-to (str reply-to))
       :payload (if (= :initialize-indices (:cmd payload))
                  (:file-raw payload)
                  (when (some? payload) (t/encode-str payload)))
       :buffer (when (some? buffer?) buffer?)})

(defn decode [^js data]
  (let [cmd (obj/get data "cmd")
        sender-id (obj/get data "senderId")
        reply-to (obj/get data "replyTo")
        payload (obj/get data "payload")
        buffer (obj/get data "buffer")]
    (d/without-nils
     {:sender-id (when sender-id (uuid sender-id))
      :reply-to (when reply-to (uuid reply-to))
      :payload (if (= cmd "initialize-indices")
                 {:cmd :initialize-indices
                  :file-raw payload}
                 (when (some? payload) (t/decode-str payload)))
      :buffer? buffer})))
