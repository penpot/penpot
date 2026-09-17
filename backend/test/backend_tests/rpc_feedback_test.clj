;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-feedback-test
  (:require
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.feedback :as feedback]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest send-user-feedback-schema-validation
  (let [schema feedback/schema:send-user-feedback]

    (t/testing "accepts valid feedback with all fields"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :type "bug"
                    :error-href "https://example.com/error"
                    :error-report "Error details here"}]
        (t/is (sm/valid? schema params))))

    (t/testing "accepts feedback without optional fields"
      (let [params {:subject "Test subject"
                    :content "Test content"}]
        (t/is (sm/valid? schema params))))

    (t/testing "accepts error-report up to 1MiB"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :error-report (apply str (repeat 1048576 "x"))}]
        (t/is (sm/valid? schema params))))

    (t/testing "rejects error-report exceeding 1MiB"
      (let [params {:subject "Test subject"
                    :content "Test content"
                    :error-report (apply str (repeat 1048577 "x"))}]
        (t/is (not (sm/valid? schema params)))))))

(t/deftest send-user-feedback-reaches-send-with-connection
  (with-redefs [cf/flags  (conj cf/flags :user-feedback)
                cf/config (assoc cf/config :user-feedback-destination "fb@example.com")]
    (let [profile  (th/create-profile* 1 {})
          captured (atom nil)]
      (with-redefs [eml/send! (fn [cfg params]
                                (reset! captured {:cfg cfg :params params})
                                nil)]
        (let [{:keys [error]} (th/command! {::th/type       :send-user-feedback
                                            ::rpc/profile-id (:id profile)
                                            :subject        "s"
                                            :content        "c"})]
          (t/is (nil? error))))
      (t/testing "send! is reached with a caller connection"
        (t/is (some? (:cfg @captured)))
        (t/is (some? (::db/conn (:cfg @captured))))))))
