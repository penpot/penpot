;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.common-test
  "Every team-scoped navigation carries a team.

  `team-container*` raises `:not-found :invalid-team-id` for a dashboard
  or workspace route without a valid `team-id`, and `:not-found` is not
  reported to the error pipeline, so a navigation that loses the id ends
  on the not-found page without leaving a trace. The helpers resolve the
  id from the argument, the initialized team, the team of the route
  already on screen, and the profile default, in that order."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))

(defn- state
  "A slice of the store with the teams a navigation can resolve. The
  route is the one a team screen leaves behind: its query names the team
  the user is looking at."
  [{:keys [current-team-id route-team-id default-team-id]}]
  (cond-> {:profile {:id (uuid/next)}}
    (some? current-team-id)
    (assoc :current-team-id current-team-id)

    (some? default-team-id)
    (assoc-in [:profile :default-team-id] default-team-id)

    (some? route-team-id)
    (assoc :route {:data {:name :dashboard-recent}
                   :params {:path {} :query {:team-id (str route-team-id)}}
                   :query-params {:team-id (str route-team-id)}})))

(defn- ^:async emitted
  "Runs `event` against `state` and returns every event it emitted, once
  the event's stream terminates.

  The stream is a subject the caller of `ptk/watch` never sees: `go-to-
  dashboard-search` keeps listening to the router after navigating and
  completes only when it receives one `::rt/navigated`, which is the event
  it waits for in the app to focus the search box. Pushing it here makes
  that helper terminate like the rest, so every event of the family is
  measured the same way, subscribe and await termination.

  `observe` forces asynchronous delivery even for a synchronous source and
  rejects when the stream does not terminate in time, so a helper that
  stops closing its stream fails here instead of passing on the first
  event."
  [event state]
  (let [stream (rx/subject)
        events (atom [])
        done   (async/observe (ptk/watch event state stream)
                              :on-next #(swap! events conj %))]
    ;; `observe` subscribes inside its promise executor, so the subject
    ;; already has a listener; a subject drops values nobody hears.
    (rx/push! stream (rt/navigated {:data {:name :dashboard-search}} false))
    (await done)
    @events))

(defn- nav-params
  "The params of the last navigation to `route-id`, or nil when the event
  emitted none."
  [events route-id]
  (some->> events
           (filter #(and (= ::rt/navigate (ptk/type %))
                         (= route-id (:id @%))))
           (last)
           (deref)
           (:params)))

(t/deftest ^:async go-to-workspace-takes-the-team-of-the-route-on-screen
  ;; The opened file belongs to the team of the route. The personal
  ;; default would open it under another team.
  (let [route-team-id (uuid/next)
        default-team-id (uuid/next)
        events (await (emitted (dcm/go-to-workspace :file-id file-id)
                               (state {:route-team-id route-team-id
                                       :default-team-id default-team-id})))]
    (t/is (= route-team-id (:team-id (nav-params events :workspace))))))

(t/deftest ^:async go-to-workspace-takes-the-team-initialized-in-the-store
  (let [current-team-id (uuid/next)
        events (await (emitted (dcm/go-to-workspace :file-id file-id)
                               (state {:current-team-id current-team-id
                                       :route-team-id (uuid/next)
                                       :default-team-id (uuid/next)})))]
    (t/is (= current-team-id (:team-id (nav-params events :workspace))))))

(t/deftest ^:async go-to-workspace-takes-the-profile-default-when-no-team-is-known
  (let [default-team-id (uuid/next)
        events (await (emitted (dcm/go-to-workspace :file-id file-id)
                               (state {:default-team-id default-team-id})))]
    (t/is (= default-team-id (:team-id (nav-params events :workspace))))))

(t/deftest ^:async go-to-workspace-keeps-the-team-the-caller-passes
  (let [team-id (uuid/next)
        events (await (emitted (dcm/go-to-workspace :team-id team-id :file-id file-id)
                               (state {:current-team-id (uuid/next)
                                       :route-team-id (uuid/next)
                                       :default-team-id (uuid/next)})))]
    (t/is (= team-id (:team-id (nav-params events :workspace))))))

(t/deftest ^:async go-to-dashboard-recent-keeps-the-default-sentinel
  (let [default-team-id (uuid/next)
        events (await (emitted (dcm/go-to-dashboard-recent :team-id :default)
                               (state {:current-team-id (uuid/next)
                                       :route-team-id (uuid/next)
                                       :default-team-id default-team-id})))]
    (t/is (= default-team-id (:team-id (nav-params events :dashboard-recent))))))

(t/deftest ^:async go-to-dashboard-deleted-keeps-the-default-sentinel
  ;; The sentinel is a second copy of the branch in `-recent`; without its
  ;; own test, a divergence here would navigate to the current team.
  (let [default-team-id (uuid/next)
        events (await (emitted (dcm/go-to-dashboard-deleted :team-id :default)
                               (state {:current-team-id (uuid/next)
                                       :route-team-id (uuid/next)
                                       :default-team-id default-team-id})))]
    (t/is (= default-team-id (:team-id (nav-params events :dashboard-deleted))))))

(t/deftest ^:async go-to-dashboard-recent-resolves-a-nil-team-through-the-route
  (let [route-team-id (uuid/next)
        events (await (emitted (dcm/go-to-dashboard-recent)
                               (state {:route-team-id route-team-id
                                       :default-team-id (uuid/next)})))]
    (t/is (= route-team-id (:team-id (nav-params events :dashboard-recent))))))

(def ^:private dashboard-helpers
  "The rest of the family, with the route each one navigates to."
  [[dcm/go-to-dashboard-files :dashboard-files]
   [dcm/go-to-dashboard-search :dashboard-search]
   [dcm/go-to-dashboard-libraries :dashboard-libraries]
   [dcm/go-to-dashboard-fonts :dashboard-fonts]
   [dcm/go-to-dashboard-members :dashboard-members]
   [dcm/go-to-dashboard-invitations :dashboard-invitations]
   [dcm/go-to-dashboard-webhooks :dashboard-webhooks]
   [dcm/go-to-dashboard-settings :dashboard-settings]
   [dcm/go-to-dashboard-deleted :dashboard-deleted]])

(t/deftest ^:async dashboard-helpers-take-the-team-of-the-route-on-screen
  (doseq [[helper route-id] dashboard-helpers]
    (let [route-team-id (uuid/next)
          events (await (emitted (helper)
                                 (state {:route-team-id route-team-id
                                         :default-team-id (uuid/next)})))]
      (t/is (= route-team-id (:team-id (nav-params events route-id)))
            (str "route: " route-id)))))

(t/deftest ^:async dashboard-helpers-take-the-profile-default-when-no-team-is-known
  (doseq [[helper route-id] dashboard-helpers]
    (let [default-team-id (uuid/next)
          events (await (emitted (helper)
                                 (state {:default-team-id default-team-id})))]
      (t/is (= default-team-id (:team-id (nav-params events route-id)))
            (str "route: " route-id)))))

(t/deftest ^:async navigations-still-fire-when-no-team-can-be-resolved
  ;; Documented last resort: a profile without a default team is a broken
  ;; or transient state, and there is nothing to resolve. The navigation
  ;; still fires, so the not-found page appears with its exit in the
  ;; header instead of the click doing nothing.
  (let [events (await (emitted (dcm/go-to-dashboard-members) (state {})))]
    (t/is (some? (nav-params events :dashboard-members)))
    (t/is (nil? (:team-id (nav-params events :dashboard-members))))))
