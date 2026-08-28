;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-websocket-test
  (:require
   [app.common.json :as json]
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.http.websocket :as http.ws]
   [clojure.test :as t]))

(t/deftest resolve-encoder-defaults-to-transit
  (let [encode (http.ws/resolve-encoder :transit)
        msg    {:type :join-file
                :file-id (uuid/next)
                :profile-id (uuid/next)}]
    (t/is (= msg (tr/decode-str (encode msg))))))

(t/deftest resolve-encoder-unexpected-format-falls-back-to-transit
  (let [encode (http.ws/resolve-encoder :foo)
        msg    {:type :join-file :file-id (uuid/next)}]
    (t/is (= msg (tr/decode-str (encode msg))))))

(t/deftest resolve-encoder-json
  (let [encode  (http.ws/resolve-encoder :json)
        file-id (uuid/next)
        encoded (encode {:type :join-file :file-id file-id})]
    (t/is (string? encoded))
    (t/is (= "join-file" (get (json/decode encoded) "type")))
    (t/is (= (str file-id) (get (json/decode encoded) "fileId")))))

(t/deftest resolve-decoder-defaults-to-transit
  (let [decode (http.ws/resolve-decoder :transit)
        msg    {:type :subscribe-file :file-id (uuid/next)}]
    (t/is (= msg (decode (tr/encode-str msg {:type :json-verbose}))))))

(t/deftest resolve-decoder-unexpected-format-falls-back-to-transit
  (let [decode (http.ws/resolve-decoder :foo)
        msg    {:type :subscribe-file :file-id (uuid/next)}]
    (t/is (= msg (decode (tr/encode-str msg {:type :json-verbose}))))))

(t/deftest resolve-decoder-json
  (let [decode (http.ws/resolve-decoder :json)
        file-id (uuid/next)
        decoded (decode (str "{\"type\":\"subscribe-file\","
                             "\"fileId\":\"" file-id "\"}"))]
    (t/is (= "subscribe-file" (:type decoded)))
    (t/is (= (str file-id) (:file-id decoded)))))

(t/deftest normalize-message-from-json-decoding
  (let [file-id (uuid/next)
        decoded {:type "subscribe-file"
                 :file-id (str file-id)}
        message (http.ws/normalize-message decoded)]
    (t/is (= :subscribe-file (:type message)))
    (t/is (uuid? (:file-id message)))
    (t/is (= file-id (:file-id message)))))

(t/deftest normalize-message-noop-for-transit-decoding
  (let [file-id (uuid/next)
        decoded {:type :subscribe-file :file-id file-id}]
    (t/is (= decoded (http.ws/normalize-message decoded)))))

(t/deftest normalize-message-with-invalid-uuid
  (let [message (http.ws/normalize-message
                 {:type "subscribe-file" :file-id "invalid"})]
    (t/is (= :subscribe-file (:type message)))
    (t/is (nil? (:file-id message)))))

(t/deftest normalize-message-team-id
  (let [team-id (uuid/next)
        message (http.ws/normalize-message
                 {:type "subscribe-team" :team-id (str team-id)})]
    (t/is (= :subscribe-team (:type message)))
    (t/is (= team-id (:team-id message)))))
