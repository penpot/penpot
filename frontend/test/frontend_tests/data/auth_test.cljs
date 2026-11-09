;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.auth-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.team :as dtm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

;; --- Event runtime helpers

(defn- ^:async watch-events
  "Runs the `watch` method of `event` with an empty state and the given
  input `stream`, and returns the events it emits."
  [event stream]
  (let [events (atom [])]
    (await (async/observe (ptk/watch event {} stream)
                          :on-next #(swap! events conj %)))
    @events))

(defn- find-data-event
  "First `ptk/data-event` in `events` whose `::ev/name` is `name`."
  [events name]
  (some (fn [event]
          (when (and (= :app.main.data.event/event (ptk/type event))
                     (= name (::ev/name (deref event))))
            event))
        events))

(defn- navigate-events
  [events]
  (filter #(= :app.main.router/navigate (ptk/type %)) events))


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

(t/deftest with-register-origin-attaches-the-invitation-token
  (let [with-register-origin @#'da/with-register-origin
        profile            (with-register-origin {:id "x"} "tok")]
    (t/is (= {:id "x" :invitation-token "tok"} profile))
    (t/is (= {::ev/source "register" ::ev/activation "immediate"}
             (meta profile)))))

(t/deftest with-register-origin-keeps-the-profile-without-a-token
  (let [with-register-origin @#'da/with-register-origin
        profile            (with-register-origin {:id "x"} nil)]
    (t/is (= {:id "x"} profile))
    (t/is (not (contains? profile :invitation-token)))
    (t/is (= {::ev/source "register" ::ev/activation "immediate"}
             (meta profile)))))

(t/deftest invitation-nav-only-emits-with-an-invitation-token
  (let [get-invitation-nav @#'da/get-invitation-nav
        collect           (fn [profile]
                            (let [events (atom [])]
                              (rx/subs! #(swap! events conj %)
                                        (get-invitation-nav profile))
                              @events))]
    (t/is (= [] (collect {:auth-backend "penpot"})))
    (let [events (collect {:auth-backend "penpot"
                           :invitation-token "tok"})
          event  (deref (first events))]
      (t/is (= 1 (count events)))
      (t/is (= :auth-verify-token (:id event)))
      (t/is (= {:token "tok"} (:params event))))))

;; --- logged-in: the invitation does not wait for the teams

(t/deftest ^:async logged-in-with-invitation-navigates-to-token-verification
  (let [logged-in     @#'da/logged-in
        origin-meta   @#'da/origin-meta
        profile       (with-meta {:id (uuid/next)
                                  :auth-backend "penpot"
                                  :invitation-token "tok"}
                        (origin-meta {:source "login"}))
        ;; The teams arrive: the invitation must not be diverted to the
        ;; default team dashboard.
        teams-fetched (ptk/data-event ::dtm/teams-fetched [])
        events        (await (watch-events (logged-in profile)
                                           (rx/of teams-fetched)))
        navs          (navigate-events events)
        nav           (some-> (first navs) deref)
        signin        (some-> (find-data-event events "signin") deref)]
    (t/is (= 1 (count navs)))
    (t/is (= :auth-verify-token (:id nav)))
    (t/is (= {:token "tok"} (:params nav)))
    (t/is (not-any? #(= :app.main.data.common/go-to-dashboard-recent (ptk/type %))
                    events))
    (t/is (true? (:from-invitation signin)))))

(t/deftest ^:async logged-in-without-invitation-goes-to-the-dashboard
  (let [logged-in     @#'da/logged-in
        origin-meta   @#'da/origin-meta
        profile       (with-meta {:id (uuid/next) :auth-backend "penpot"}
                        (origin-meta {:source "login"}))
        teams-fetched (ptk/data-event ::dtm/teams-fetched [])
        events        (await (watch-events (logged-in profile)
                                           (rx/of teams-fetched)))]
    (t/is (empty? (navigate-events events)))
    (t/is (some #(= :app.main.data.common/go-to-dashboard-recent (ptk/type %))
                events))))

;; --- login-from-token: the invitation token survives the decoration

(t/deftest ^:async login-from-token-with-invitation-keeps-the-token
  (let [[event] (await (watch-events
                        (da/login-from-token
                         {:profile {:id (uuid/next) :auth-backend "penpot"}
                          :iss :auth
                          :invitation-token "tok"})
                        (rx/empty)))
        events  (await (watch-events event (rx/empty)))
        signin  (some-> (find-data-event events "signin") deref)]
    (t/is (= :app.main.data.auth/logged-in (ptk/type event)))
    (t/is (= "login" (:source signin)))
    (t/is (true? (:from-invitation signin)))))

(t/deftest ^:async login-from-token-without-invitation-has-no-invitation
  (let [[event] (await (watch-events
                        (da/login-from-token
                         {:profile {:id (uuid/next) :auth-backend "penpot"}
                          :iss :auth})
                        (rx/empty)))
        events  (await (watch-events event (rx/empty)))
        signin  (some-> (find-data-event events "signin") deref)]
    (t/is (false? (:from-invitation signin)))))
