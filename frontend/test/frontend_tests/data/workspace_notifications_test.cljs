;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-notifications-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.notifications :as dwntf]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
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

(defn- ^:async shown-notice
  "The notification the watch of `event` shows on `state`."
  [event state]
  (let [events (atom [])]
    (await (async/observe (ptk/watch event state (rx/empty))
                          :on-next #(swap! events conj %)))
    (some #(when (= ::ntf/show (ptk/type %))
             (:notification (ptk/update % {})))
          @events)))

(t/deftest ^:async repaired-file-change-applies-and-notifies
  (let [events (await (watch-file-change (file-change-msg {:repaired true})))
        notice (await (shown-notice (second events) {}))]
    (t/is (= [::dch/commit ::dps/notify-repaired] (mapv ptk/type events)))
    (t/is (= :info (:level notice)))
    (t/is (nil? (:timeout notice)) "the toast stays until the user closes it")
    (t/is (= dps/file-repaired-tag (:tag notice)))
    (t/is (= (i18n/tr "workspace.notifications.file-repaired") (:content notice)))))

;; Scenario: a save of the open file came back rejected and its notice is
;; still on screen when the backend repairs the file. The store holds one
;; toast. Proves: the repair notice tells both and keeps the report link.
(t/deftest ^:async a-repair-notice-keeps-a-rejection-notice-on-screen
  (let [link   {:label "report.txt" :callback identity}
        notice (await (shown-notice (dps/notify-repaired)
                                    {:notification {:content "rejected"
                                                    :tag dps/rejected-tag
                                                    :links [link]}}))]
    (t/is (= (i18n/tr "workspace.notifications.file-repaired-changes-undone")
             (:content notice)))
    (t/is (= :warning (:level notice)))
    (t/is (= dps/repaired-rejected-tag (:tag notice)))
    (t/is (= [link] (:links notice)))
    (t/is (nil? (:timeout notice)))))

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
    (t/is (= "Ada" (get-in modal [:props :fullname])))
    (t/is (false? (get-in modal [:props :unsaved])))))

;; Scenario: a peer restores a version they named, while this tab has a
;; rename not saved yet and the restorer has no presence. Proves: the modal
;; names the restorer by profile and the version by its label, and says the
;; unsaved rename is lost.
(t/deftest ^:async a-peer-restore-names-the-version-and-the-lost-edits
  (let [file-id    (uuid/next)
        profile-id (uuid/next)
        created-at (ct/now)
        commit-id  (uuid/next)
        state      {:current-file-id file-id
                    :files {file-id {:id file-id :vern 1}}
                    :profiles {profile-id {:id profile-id :fullname "Ada"}}
                    :persistence {:queue [commit-id]
                                  :index {commit-id {:id commit-id
                                                     :file-id file-id
                                                     :file-vern 1
                                                     :commits [{:redo-changes [{:type :mod-page}]}]}}}}
        events     (await (restored-events state {:type :file-restored
                                                  :file-id file-id
                                                  :session-id (uuid/next)
                                                  :profile-id profile-id
                                                  :label "Hand-off v2"
                                                  :created-at created-at
                                                  :vern 2}))
        props      (:props (shown-modal events))]
    (t/is (= "Ada" (:fullname props)))
    (t/is (= "Hand-off v2" (:label props)))
    (t/is (= created-at (:created-at props)))
    (t/is (true? (:unsaved props)))))

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

(defn- ^:async subscription-messages
  "Runs `scenario` with a store over `team-id`, `dws/send` and the pointer
  handler stubbed. `scenario` gets the store and a function that delivers
  a peer's pointer message for a file. Resolves to the messages sent over
  the websocket and the number of handled pointer messages."
  [team-id scenario]
  (let [sent    (atom [])
        handled (atom 0)
        result  (atom nil)
        store   (ptk/store {:state {:profile-id (uuid/next)
                                    :session-id (uuid/next)
                                    :teams {team-id {:id team-id}}}
                            :on-error #(t/is false (str %))})
        pointer (fn [file-id]
                  (ptk/emit! store (ptk/data-event ::dws/message
                                                   {:type :pointer-update
                                                    :topic file-id
                                                    :file-id file-id
                                                    :session-id (uuid/next)})))]
    (await
     (mock/with-mocks*
       {dws/send                    (mock/stub (fn [msg]
                                                 (swap! sent conj msg)
                                                 (ptk/data-event ::sent msg)))
        dwntf/handle-pointer-update (mock/stub (fn [_]
                                                 (swap! handled inc)
                                                 (ptk/data-event ::handled {})))}
       (try
         (await (scenario store pointer))
         ;; Taken before the store ends, which ends the subscriptions too
         (reset! result {:sent (mapv #(select-keys % [:type :file-id]) @sent)
                         :handled @handled})
         (finally
           (rx/dispose! store)))))
    @result))

;; Scenario: the open file reloads three times (a peer's restore, the
;; restorer's own version reload, the rollback fallback), each starting a
;; new subscription, and then a peer's message arrives. Proves: one live
;; subscription per open file, so each message is handled once, and no
;; unsubscribe ends the server's subscription of the reloaded file.
(t/deftest ^:async a-reload-replaces-the-file-subscription
  (let [team-id (uuid/next)
        file-id (uuid/next)
        result  (await
                 (subscription-messages
                  team-id
                  (^:async fn [store pointer]
                    (ptk/emit! store
                               (dwntf/initialize team-id file-id)
                               (dwntf/initialize team-id file-id)
                               (dwntf/initialize team-id file-id))
                    (await (async/settle))
                    (pointer file-id)
                    (await (async/settle)))))]
    (t/is (= 1 (:handled result)) "the message is handled once")
    (t/is (= 3 (count (filter #(= :subscribe-file (:type %)) (:sent result)))))
    (t/is (not-any? #(= :unsubscribe-file (:type %)) (:sent result)))))

;; Scenario: the user leaves a file, and in another case opens another file
;; without leaving the first one. Proves: the subscription ends with an
;; unsubscribe of the file it was for, and the old file's messages are no
;; longer handled.
(t/deftest ^:async leaving-or-switching-files-ends-the-file-subscription
  (let [team-id (uuid/next)
        file-a  (uuid/next)
        file-b  (uuid/next)
        left    (await
                 (subscription-messages
                  team-id
                  (^:async fn [store _]
                    (ptk/emit! store
                               (dwntf/initialize team-id file-a)
                               (dwntf/finalize file-a))
                    (await (async/settle)))))
        switch  (await
                 (subscription-messages
                  team-id
                  (^:async fn [store pointer]
                    (ptk/emit! store
                               (dwntf/initialize team-id file-a)
                               (dwntf/initialize team-id file-b))
                    (await (async/settle))
                    (pointer file-a)
                    (pointer file-b)
                    (await (async/settle)))))]
    (t/is (= [{:type :unsubscribe-file :file-id file-a}]
             (filterv #(= :unsubscribe-file (:type %)) (:sent left))))
    (t/is (= [{:type :unsubscribe-file :file-id file-a}]
             (filterv #(= :unsubscribe-file (:type %)) (:sent switch))))
    (t/is (= 1 (:handled switch)) "only the new file's message is handled")))
