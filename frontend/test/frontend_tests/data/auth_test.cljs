;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.auth-test
  (:require
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [cljs.test :as t :include-macros true]))

(t/deftest auth-origin-keeps-only-the-fields-that-apply
  (t/is (= {:source "login" :auth-backend "penpot"}
           (da/auth-origin {:source "login" :auth-backend "penpot"})))
  (t/is (= {:source "register" :auth-backend "google"
            :activation "email-verification"}
           (da/auth-origin {:source "register" :auth-backend "google"
                            :activation "email-verification"})))
  (t/is (= {:source "register" :auth-backend "penpot"
            :from-invitation true}
           (da/auth-origin {:source "register" :auth-backend "penpot"
                            :from-invitation true}))))

(t/deftest auth-origin-rejects-values-outside-the-vocabulary
  (t/is (thrown? js/Error
                 (da/auth-origin {:source "login-with-token"
                                  :auth-backend "penpot"})))
  (t/is (thrown? js/Error
                 (da/auth-origin {:source "login"
                                  :auth-backend "foo"}))))

(t/deftest origin-meta-omits-the-fields-that-do-not-apply
  (let [origin-meta @#'da/origin-meta]
    (t/is (= {::ev/source "login"}
             (origin-meta {:source "login"})))
    (t/is (= {::ev/source "register" ::ev/activation "immediate"}
             (origin-meta {:source "register" :activation "immediate"})))))

(t/deftest iss-maps-to-an-origin
  (let [iss->origin @#'da/iss->origin]
    (t/is (= {:source "login"} (iss->origin :auth)))
    (t/is (= {:source "register" :activation "email-verification"}
             (iss->origin :verify-email)))
    (t/is (nil? (iss->origin :unknown)))))

(t/deftest profile-origin-composes-the-event-props
  (let [profile-origin @#'da/profile-origin]
    (t/is (= {:source "login" :auth-backend "google"
              :from-invitation false}
             (profile-origin
              (with-meta {:auth-backend "google"} {::ev/source "login"}))))
    (t/is (= {:source "login" :auth-backend "google"
              :from-invitation true}
             (profile-origin
              (with-meta {:auth-backend "google" :invitation-token "tok"}
                {::ev/source "login"}))))
    (t/is (= {:source "register" :auth-backend "penpot"
              :activation "email-verification"
              :from-invitation false}
             (profile-origin
              (with-meta {:auth-backend "penpot"}
                {::ev/source "register" ::ev/activation "email-verification"}))))))
