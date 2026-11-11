;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-audit-test
  "Tests for the anonymisation filter applied to the telemetry shadow rows."
  (:require
   [app.common.uuid :as uuid]
   [app.loggers.audit :as audit]
   [clojure.test :as t]))

(def ^:private profile-id (uuid/next))

(defn- shadow-props
  [event]
  (:props (audit/filter-telemetry-props event)))

(t/deftest identify-keeps-the-session-origin-fields
  (let [props (shadow-props {:source "frontend"
                             :type "identify"
                             :name "signin"
                             :props {:id profile-id
                                     :auth-backend "penpot"
                                     :source "register"
                                     :activation "immediate"}})]
    (t/is (= "register" (:source props)))
    (t/is (= "immediate" (:activation props)))
    (t/is (= "penpot" (:auth-backend props)))
    (t/is (= profile-id (:id props)))))

(t/deftest identify-drops-personal-data-and-derives-the-domain
  (let [props (shadow-props {:source "frontend"
                             :type "identify"
                             :name "signin"
                             :props {:id profile-id
                                     :email "someone@example.com"
                                     :fullname "Some One"}})]
    (t/is (not (contains? props :email)))
    (t/is (not (contains? props :fullname)))
    (t/is (= "example.com" (:email-domain props)))))

(t/deftest backend-auth-event-keeps-activation-and-drops-action
  (let [props (shadow-props {:source "backend"
                             :type "action"
                             :name "register-profile"
                             :props {:id profile-id
                                     :activation "email-verification"
                                     :action "register"}})]
    (t/is (= "email-verification" (:activation props)))
    (t/is (not (contains? props :action)))))

(t/deftest absent-origin-fields-are-not-emitted-as-nil
  (let [props (shadow-props {:source "frontend"
                             :type "identify"
                             :name "signin"
                             :props {:id profile-id}})]
    (t/is (not (contains? props :source)))
    (t/is (not (contains? props :activation)))
    (t/is (not (contains? props :auth-backend)))
    (t/is (not (contains? props :email-domain)))))

(t/deftest strings-outside-the-allowlist-are-dropped
  (let [props (shadow-props {:source "frontend"
                             :type "identify"
                             :name "signin"
                             :props {:id profile-id
                                     :fullname "Some One"
                                     :anything "else"}})]
    (t/is (not (contains? props :fullname)))
    (t/is (not (contains? props :anything)))))

(t/deftest events-outside-the-auth-branch-keep-the-default-filter
  (let [props (shadow-props {:source "backend"
                             :type "action"
                             :name "create-file"
                             :props {:id profile-id
                                     :team-id profile-id
                                     :activation "immediate"}})]
    (t/is (= profile-id (:id props)))
    (t/is (= profile-id (:team-id props)))
    (t/is (not (contains? props :activation)))))
