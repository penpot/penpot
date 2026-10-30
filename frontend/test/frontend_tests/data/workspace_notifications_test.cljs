;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-notifications-test
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.notifications :as dwntf]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(defn- file-change-msg
  [extra]
  (merge {:type :file-change
          :profile-id (uuid/next)
          :file-id (uuid/next)
          :session-id (uuid/next)
          :revn 1
          :vern 0
          :changes []}
         extra))

(defn- ^:async watch-file-change
  [msg]
  (let [events (atom [])]
    (await (async/observe (ptk/watch (dwntf/handle-file-change msg) {} (rx/empty))
                          :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async repaired-file-change-applies-and-notifies
  (let [events (await (watch-file-change (file-change-msg {:repaired true})))
        types  (mapv ptk/type events)
        show   (d/seek #(= ::ntf/show (ptk/type %)) events)
        state  (ptk/update show {})]
    (t/is (= [::dch/commit ::ntf/show] types))
    (t/is (= :info (get-in state [:notification :level])))
    (t/is (nil? (get-in state [:notification :timeout]))
          "the toast stays until the user closes it")
    (t/is (= (i18n/tr "workspace.notifications.file-repaired")
             (get-in state [:notification :content])))))

(t/deftest ^:async ordinary-file-change-does-not-notify
  (let [events (await (watch-file-change (file-change-msg {})))]
    (t/is (= [::dch/commit] (mapv ptk/type events)))))

(defn- restored-events
  "Events the watch of `handle-file-restored` emits for `msg` on `state`."
  [state msg]
  (let [events (atom [])]
    (-> (async/observe (ptk/watch (dwntf/handle-file-restored msg) state nil)
                       :on-next #(swap! events conj %))
        (.then (fn [_] @events)))))

(defn- shown-modal
  [events]
  (some #(when (= ::modal/show-modal (ptk/type %))
           (::modal/modal (ptk/update % {})))
        events))

;; Scenario: a peer restores an earlier version of the open file. The file
;; reloads and a modal names the peer. Proves: the reload is not silent.
(t/deftest ^:async a-peer-restore-reloads-and-names-the-peer
  (let [file-id    (uuid/next)
        session-id (uuid/next)
        profile-id (uuid/next)
        state      {:current-file-id file-id
                    :files {file-id {:id file-id :vern 1}}
                    :workspace-presence {session-id {:profile-id profile-id}}
                    :profiles {profile-id {:id profile-id :fullname "Ada"}}}
        events     (await (restored-events state {:type :file-restored
                                                  :file-id file-id
                                                  :session-id session-id
                                                  :vern 2}))
        modal      (shown-modal events)]
    (t/is (some #(= ::dw/reload-current-file (ptk/type %)) events))
    (t/is (= :file-restored (:type modal)))
    (t/is (= "Ada" (get-in modal [:props :fullname])))))

;; Scenario: the restoring session left before its message arrived, so it
;; has no presence. Proves: the modal still shows, without a name.
(t/deftest ^:async a-restore-from-an-unknown-session-shows-no-name
  (let [file-id (uuid/next)
        state   {:current-file-id file-id
                 :files {file-id {:id file-id :vern 1}}}
        events  (await (restored-events state {:type :file-restored
                                               :file-id file-id
                                               :session-id (uuid/next)
                                               :vern 2}))
        modal   (shown-modal events)]
    (t/is (= :file-restored (:type modal)))
    (t/is (nil? (get-in modal [:props :fullname])))))

;; Scenario: the file already shows the restored version. Proves: no reload
;; and no modal for a version the user already sees.
(t/deftest ^:async a-restore-of-the-loaded-version-does-nothing
  (let [file-id (uuid/next)
        state   {:current-file-id file-id
                 :files {file-id {:id file-id :vern 2}}}
        events  (await (restored-events state {:type :file-restored
                                               :file-id file-id
                                               :session-id (uuid/next)
                                               :vern 2}))]
    (t/is (empty? events))))
