;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-websocket-test
  (:require
   [app.common.json :as json]
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.session :as-alias session]
   [app.http.websocket :as http.ws]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.util.websocket :as ws]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [promesa.exec.csp :as sp]
   [yetti.websocket :as yws]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn make-wsp
  [profile-id state output-ch]
  {::ws/id (uuid/next)
   ::ws/state state
   ::ws/output-ch output-ch
   ::http.ws/profile-id profile-id
   ::http.ws/session-id (uuid/next)})

(t/deftest subscribe-file-permission-check
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile1)
                                     :project-id (:default-project-id profile1)})
        cfg      th/*system*
        state    (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))]

    (t/testing "rejects unauthorized user"
      (let [wsp (make-wsp (:id profile2) state output-ch)]
        (t/is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"not found"
               ((get-method http.ws/handle-message :subscribe-file)
                cfg wsp {:file-id (:id file)})))))

    (t/testing "permission check passes for authorized user"
      (t/is (nil? (files/check-read-permissions! cfg (:id profile1) (:id file)))))))

(t/deftest subscribe-team-permission-check
  (let [profile1 (th/create-profile* 1 {:is-active true})
        profile2 (th/create-profile* 2 {:is-active true})
        team     (th/create-team* 1 {:profile-id (:id profile1)})
        cfg      th/*system*
        state    (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))]

    (t/testing "rejects unauthorized user"
      (let [wsp (make-wsp (:id profile2) state output-ch)]
        (t/is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"not found"
               ((get-method http.ws/handle-message :subscribe-team)
                cfg wsp {:team-id (:id team)})))))

    (t/testing "permission check passes for authorized user"
      (t/is (nil? (teams/check-read-permissions! cfg (:id profile1) (:id team)))))))

(defn- subscribed-topics
  "Runs :subscribe-team for `profile-id` on `team-id` with `nitrate-call`
  standing in for nitrate; returns the topics and the stored subscription."
  [profile-id team-id nitrate-call]
  (let [state     (atom {})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))
        wsp       (make-wsp profile-id state output-ch)
        calls     (atom [])]
    (with-redefs [nitrate/call nitrate-call
                  mbus/sub!    (fn [_ & {:keys [topics]}]
                                 (swap! calls conj topics))]
      ((get-method http.ws/handle-message :subscribe-team)
       th/*system* wsp {:team-id team-id}))
    (some-> @state ::http.ws/team-subscription :channel sp/close!)
    {:topics @calls
     :subscription (::http.ws/team-subscription @state)}))

(t/deftest subscribe-team-subscribes-to-team-organization
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:id (th/create-team* 1 {:profile-id (:id profile)}))
        org-id  (uuid/next)]

    (t/testing "adds the organization topic when the team has one"
      (let [{:keys [topics subscription]}
            (subscribed-topics (:id profile) team-id
                               (fn [_ method params]
                                 (when (= :get-team-organization method)
                                   {:id (:team-id params)
                                    :organization {:id org-id}})))]
        (t/is (= [[team-id org-id]] topics))
        (t/is (= org-id (:organization-id subscription)))))

    (t/testing "subscribes only to the team when it has no organization"
      (let [{:keys [topics subscription]}
            (subscribed-topics (:id profile) team-id (constantly nil))]
        (t/is (= [[team-id]] topics))
        (t/is (nil? (:organization-id subscription)))))

    (t/testing "subscribes to the team when nitrate fails"
      (let [{:keys [topics]}
            (subscribed-topics (:id profile) team-id
                               (fn [& _] (throw (ex-info "nitrate down" {}))))]
        (t/is (= [[team-id]] topics))))))

(t/deftest pointer-update-validates-file-id
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        cfg      th/*system*
        file-id  (:id file)
        sub-ch   (sp/chan :buf (sp/dropping-buffer 64))
        state    (atom {::http.ws/file-subscription {:file-id file-id
                                                     :channel sub-ch
                                                     :topic file-id}})
        output-ch (sp/chan :buf (sp/dropping-buffer 64))
        wsp      (make-wsp (:id profile) state output-ch)]

    (t/testing "skips publish when file-id does not match subscription"
      (let [wrong-msg {:type :pointer-update
                       :file-id (uuid/next)
                       :position {:x 10 :y 20}
                       :zoom 1.0}]
        (t/is (nil?
               ((get-method http.ws/handle-message :pointer-update)
                cfg wsp wrong-msg)))))

    (t/testing "does nothing when no file subscription exists"
      (let [empty-state (atom {})
            empty-wsp   (make-wsp (:id profile) empty-state output-ch)
            msg         {:type :pointer-update
                         :file-id file-id
                         :position {:x 10 :y 20}
                         :zoom 1.0}]
        (t/is (nil?
               ((get-method http.ws/handle-message :pointer-update)
                cfg empty-wsp msg)))))))

(t/deftest resolve-encoder-defaults-to-transit
  (let [encode (ws/resolve-encoder :transit)
        msg    {:type :join-file
                :file-id (uuid/next)
                :profile-id (uuid/next)}]
    (t/is (= msg (tr/decode-str (encode msg))))))

(t/deftest resolve-encoder-unexpected-format-falls-back-to-transit
  (let [encode (ws/resolve-encoder :foo)
        msg    {:type :join-file :file-id (uuid/next)}]
    (t/is (= msg (tr/decode-str (encode msg))))))

(t/deftest resolve-encoder-json
  (let [encode  (ws/resolve-encoder :json)
        file-id (uuid/next)
        encoded (encode {:type :join-file :file-id file-id})]
    (t/is (string? encoded))
    (t/is (= "join-file" (get (json/decode encoded) "type")))
    (t/is (= (str file-id) (get (json/decode encoded) "fileId")))))

(t/deftest resolve-decoder-defaults-to-transit
  (let [decode (ws/resolve-decoder :transit)
        msg    {:type :subscribe-file :file-id (uuid/next)}]
    (t/is (= msg (decode (tr/encode-str msg {:type :json-verbose}))))))

(t/deftest resolve-decoder-unexpected-format-falls-back-to-transit
  (let [decode (ws/resolve-decoder :foo)
        msg    {:type :subscribe-file :file-id (uuid/next)}]
    (t/is (= msg (decode (tr/encode-str msg {:type :json-verbose}))))))

(t/deftest resolve-decoder-json
  (let [decode (ws/resolve-decoder :json)
        file-id (uuid/next)
        decoded (decode (str "{\"type\":\"subscribe-file\","
                             "\"fileId\":\"" file-id "\"}"))]
    (t/is (= "subscribe-file" (:type decoded)))
    (t/is (= (str file-id) (:file-id decoded)))))

(t/deftest normalize-message-from-json-decoding
  (let [file-id (uuid/next)
        decoded {:type "subscribe-file"
                 :file-id (str file-id)}
        message (ws/normalize-message decoded)]
    (t/is (= :subscribe-file (:type message)))
    (t/is (uuid? (:file-id message)))
    (t/is (= file-id (:file-id message)))))

(t/deftest normalize-message-noop-for-transit-decoding
  (let [file-id (uuid/next)
        decoded {:type :subscribe-file :file-id file-id}]
    (t/is (= decoded (ws/normalize-message decoded)))))

(t/deftest normalize-message-with-invalid-uuid
  (let [message (ws/normalize-message
                 {:type "subscribe-file" :file-id "invalid"})]
    (t/is (= :subscribe-file (:type message)))
    (t/is (nil? (:file-id message)))))

(t/deftest normalize-message-team-id
  (let [team-id (uuid/next)
        message (ws/normalize-message
                 {:type "subscribe-team" :team-id (str team-id)})]
    (t/is (= :subscribe-team (:type message)))
    (t/is (= team-id (:team-id message)))))

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

(defn- ws-request
  [{:keys [headers params]}]
  {:params (merge {:session-id (str (uuid/next))} params)
   ::session/profile-id (uuid/next)
   :headers (merge {"upgrade" "websocket"
                    "connection" "Upgrade"}
                   headers)})

(defn- listener-options
  [request]
  (let [response (http.ws/http-handler {} request)
        listener (::yws/listener response)]
    (t/is (map? listener))
    (t/is (fn? (:on-open listener)))
    (t/is (fn? (:on-close listener)))
    (t/is (fn? (:on-error listener)))
    (t/is (fn? (:on-message listener)))
    (t/is (fn? (:on-pong listener)))
    (::ws/options (meta listener))))

(t/deftest http-handler-negotiates-json-codecs
  (let [request (ws-request {:headers {"accept" "application/json"}})
        options (listener-options request)
        encode  (::ws/encode-fn options)
        decode  (::ws/decode-fn options)
        file-id (uuid/next)
        raw     (str "{\"type\":\"subscribe-file\",\"requestId\":42,"
                     "\"fileId\":\"" file-id "\"}")]
    (let [decoded (decode raw)]
      (t/is (= "subscribe-file" (:type decoded)))
      (t/is (= 42 (:request-id decoded)))
      (t/is (= (str file-id) (:file-id decoded))))
    (let [encoded (encode {:type :join-file
                           :file-id file-id
                           :request-id 42})]
      (t/is (string? encoded))
      (t/is (= "join-file" (get (json/decode encoded) "type")))
      (t/is (= 42 (get (json/decode encoded) "requestId")))
      (t/is (= (str file-id) (get (json/decode encoded) "fileId"))))))

(t/deftest http-handler-negotiates-json-codecs-from-fmt-param
  (let [request (-> (ws-request {:headers {"accept" "application/transit+json"}})
                    (assoc :query-params {:_fmt "json"})
                    (assoc-in [:params :_fmt] "json"))
        options (listener-options request)
        encoded ((::ws/encode-fn options) {:type :join-file})]
    (t/is (string? encoded))
    (t/is (= "join-file" (get (json/decode encoded) "type")))))

(t/deftest http-handler-defaults-to-transit-codecs
  (let [request (ws-request {})
        options (listener-options request)
        encode  (::ws/encode-fn options)
        decode  (::ws/decode-fn options)
        msg     {:type :join-file :file-id (uuid/next)}]
    (t/is (= msg (decode (encode msg))))))
