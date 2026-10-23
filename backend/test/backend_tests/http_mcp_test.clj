;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-mcp-test
  (:require
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as email]
   [app.http :as http]
   [app.jobs :as jobs]
   [app.redis :as rds]
   [app.rpc.commands.access-token :as access-token]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private feedback
  {:subject "Export fails"
   :feedback "Exporting a valid shape repeatedly fails. Expected a PNG."})

(defn- create-identity
  []
  (let [profile (th/create-profile* (uuid/next) {:is-active true})
        token   (db/tx-run! th/*system* access-token/create-access-token
                            (:id profile) "MCP" nil "mcp")]
    {:profile profile :token token}))

(defn- request
  ([token] (request token feedback))
  ([token params] (request token params {}))
  ([token params options]
   (with-redefs [cf/config (assoc cf/config :mcp-feedback-email "mcp@example.com")]
     ((#'http/router-handler (::http/router th/*system*))
      (th/make-dummy-request
       (merge {:path "/api/mcp/feedback"
               :method :post
               :headers (cond-> {"content-type" "application/json"
                                 "accept" "application/json"}
                          token (assoc "authorization" (str "Bearer " token)))
               :body-bytes (json/encode params)}
              options))))))

(defn- emails
  []
  (map #(jobs/decode-params (jobs/get-job-def (::jobs/defs th/*system*) :sendmail) (:params %))
       (db/query th/*system* :job {:name "sendmail"})))

(t/deftest queues-plain-text-feedback
  (let [{:keys [token profile]} (create-identity)
        response (request (:token token))
        email    (first (emails))]
    (t/is (= 202 (::yres/status response)))
    (t/is (= 1 (count (emails))))
    (t/is (= "mcp@example.com" (:to email)))
    (t/is (= (:email profile) (:reply-to email)))
    (t/is (= "[MCP feedback] Export fails" (:subject email)))
    (t/is (= {"text/plain" (str "Submitted by: " (:fullname profile)
                                " <" (:email profile) ">\n"
                                "Profile ID: " (:id profile) "\n\n"
                                "Feedback:\n\n" (:feedback feedback))}
             (:body email)))
    (t/is (nil? (:attachments email)))))

(t/deftest rejects-invalid-credentials
  (let [{:keys [profile token]} (create-identity)]
    (doseq [credential [nil "" "invalid"
                        (tokens/generate th/*system* {:iss "access-token"
                                                      :uid (:id profile) :tid (:id token)})
                        (tokens/generate th/*system* {:iss "urn:penpot:mcp-token"
                                                      :uid (uuid/next) :tid (:id token)})
                        (tokens/generate th/*system* {:iss "urn:penpot:mcp-token"
                                                      :uid (:id profile) :tid (uuid/next)})
                        (tokens/generate th/*system* {:iss "urn:penpot:mcp-token"
                                                      :uid (:id profile) :tid (:id token)
                                                      :exp (ct/minus (ct/now) (ct/duration "1h"))})]]
      (t/is (= 401 (::yres/status (request credential)))))
    (t/is (empty? (emails)))))

(t/deftest rejects-expired-revoked-and-wrong-type-tokens
  (let [{:keys [token profile]} (create-identity)]
    (db/update! th/*system* :access-token
                {:expires-at (ct/minus (ct/now) (ct/duration "1h"))} {:id (:id token)})
    (t/is (= 401 (::yres/status (request (:token token)))))
    (db/update! th/*system* :access-token {:expires-at nil :type "personal"} {:id (:id token)})
    (t/is (= 401 (::yres/status (request (:token token)))))
    (db/update! th/*system* :access-token {:type "mcp"} {:id (:id token)})
    (db/tx-run! th/*system* access-token/create-access-token (:id profile) "Replacement" nil "mcp")
    (t/is (= 401 (::yres/status (request (:token token)))))
    (t/is (empty? (emails)))))

(t/deftest rejects-unavailable-profiles
  (doseq [changes [{:is-active false}
                   {:is-blocked true}
                   {:deleted-at (ct/minus (ct/now) (ct/duration "1h"))}]]
    (let [{:keys [token profile]} (create-identity)]
      (db/update! th/*system* :profile changes {:id (:id profile)})
      (t/is (= 401 (::yres/status (request (:token token)))))))
  (t/is (empty? (emails))))

(t/deftest validates-feedback-before-queueing
  (let [{:keys [token]} (create-identity)]
    (doseq [params [{}
                    (assoc feedback :subject "")
                    (assoc feedback :subject "  ")
                    (assoc feedback :subject "a\r\nBcc: victim@example.com")
                    (assoc feedback :subject (apply str (repeat 121 "x")))
                    (assoc feedback :feedback " \n")
                    (assoc feedback :feedback (apply str (repeat 5001 "x")))
                    (assoc feedback :to "victim@example.com")
                    (assoc feedback :attachments {})
                    (assoc feedback :feedback 42)]]
      (t/is (= 400 (::yres/status (request (:token token) params)))))
    (t/is (empty? (emails)))
    (t/is (= 202 (::yres/status
                  (request (:token token) {:subject (apply str (repeat 120 "x"))
                                           :feedback (apply str (repeat 5000 "x"))}))))))

(t/deftest limits-submissions-per-profile
  (let [{:keys [token profile]} (create-identity)]
    (dotimes [_ 5]
      (t/is (= 202 (::yres/status (request (:token token))))))
    (let [replacement (db/tx-run! th/*system* access-token/create-access-token
                                  (:id profile) "Replacement" nil "mcp")
          response    (request (:token replacement))]
      (t/is (= 429 (::yres/status response)))
      (t/is (pos? (parse-long (get-in response [::yres/headers "retry-after"])))))
    (t/is (= 5 (count (emails))))
    (let [other (create-identity)]
      (t/is (= 202 (::yres/status (request (get-in other [:token :token]))))))))

(t/deftest disabled-without-feedback-email
  (let [{:keys [token]} (create-identity)]
    (with-redefs [cf/config (dissoc cf/config :mcp-feedback-email)]
      (let [response ((#'http/router-handler (::http/router th/*system*))
                      (th/make-dummy-request
                       {:path "/api/mcp/feedback" :method :post
                        :headers {"authorization" (str "Bearer " (:token token))
                                  "content-type" "application/json"}
                        :body-bytes (json/encode feedback)}))]
        (t/is (= 503 (::yres/status response)))))
    (t/is (empty? (emails)))))

(t/deftest feedback-is-post-only
  (let [{:keys [token]} (create-identity)]
    (t/is (= 405 (::yres/status (request (:token token) feedback {:method :get})))))
  (t/is (empty? (emails))))

(t/deftest mcp-token-does-not-authorize-ordinary-rpc
  (let [{:keys [token]} (create-identity)]
    (t/is (= 401 (::yres/status
                  (request (:token token) {} {:path "/api/rpc/command/get-teams"}))))))

(t/deftest bearer-token-is-independent-of-session-cookies
  (let [{:keys [token]} (create-identity)]
    (t/is (= 202 (::yres/status
                  (request (:token token) feedback
                           {:cookies {(cf/get :auth-token-cookie-name) "invalid-cookie"}}))))))

(t/deftest does-not-queue-when-the-limiter-is-unavailable
  (let [{:keys [token]} (create-identity)]
    (with-redefs [rds/run! (fn [& _] (throw (ex-info "Redis unavailable" {})))]
      (t/is (= 500 (::yres/status (request (:token token)))))))
  (t/is (empty? (emails))))

(t/deftest does-not-acknowledge-a-failed-queue-submission
  (let [{:keys [token]} (create-identity)]
    (with-redefs [email/send (fn [& _] (throw (ex-info "Queue unavailable" {})))]
      (t/is (= 500 (::yres/status (request (:token token)))))))
  (t/is (empty? (emails))))
