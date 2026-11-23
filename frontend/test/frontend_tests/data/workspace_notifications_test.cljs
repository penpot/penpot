;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-notifications-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.persistence.order :as porder]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.notifications :as dwntf]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.versions :as dwv]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.datafy :refer [datafy]]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.persistence :as thp]
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
  (let [events (atom [])
        state  {:current-file-id (:file-id msg)
                :files {(:file-id msg) {:data {} :revn 0 :vern 0 :is-shared true}}}]
    (await (async/observe (ptk/watch (dwntf/handle-file-change msg) state (rx/empty))
                          :on-next #(swap! events conj %)))
    (let [change (first @events)
          state  (ptk/update change state)]
      (await (async/observe (ptk/watch change state (rx/empty))
                            :on-next #(swap! events conj %))))
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
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store]}]
       (let [page-id  (uuid/next)
             shape-id (uuid/next)]
         (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Original"))
                    (dwntf/handle-file-change
                     (file-change-msg {:file-id file-id :repaired true
                                       :changes [(thp/rename page-id shape-id "Repaired")]})))
         (await (async/wait-for-store store #(:notification @store) "the repair is announced"))
         (let [notice (:notification @store)]
           (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= :info (:level notice)))
           (t/is (nil? (:timeout notice)) "the toast stays until the user closes it")
           (t/is (= dps/file-repaired-tag (:tag notice)))
           (t/is (= (i18n/tr "workspace.notifications.file-repaired") (:content notice)))))))))

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
    (t/is (= ::dch/rebase-commits (ptk/type (first events))))
    (t/is (not-any? (ptk/type? ::dps/notify-repaired) events))))

(defn- library-change-msg
  [extra]
  (file-change-msg (merge {:type :library-change :modified-at (ct/now)} extra)))

(defn- library-color
  [id name]
  {:id id :name name :color "#224466" :opacity 1})

(defn- get-color-name
  [store file-id color-id]
  (get-in @store [:files file-id :data :colors color-id :name]))

(defn- get-file-reads
  [requests]
  (into [] (keep (fn [[command params]] (when (= command :get-file) (:id params)))) @requests))

(defn- websocket-message
  [msg]
  (ptk/data-event ::dws/message msg))

(defn- library-effect?
  [event]
  (contains? #{::dwl/library-change-effects ::dwl/notify-sync-file
               ::dwtp/propagate-workspace-tokens}
             (ptk/type event)))

(t/deftest ^:async an-unmarked-delta-does-not-run-effects-just-because-the-open-file-is-shared
  (let [set-id (uuid/next)
        events (await (watch-file-change
                       (file-change-msg {:changes [{:type :set-token-set :id set-id
                                                    :attrs {:id set-id :name "Peer set"}}]})))]
    (t/is (= ::dch/rebase-commits (ptk/type (first events))))
    (t/is (some (ptk/type? ::dps/record-remote-changes) events))
    (t/is (not-any? library-effect? events))))

(t/deftest a-file-delta-rejects-a-non-boolean-library-flag
  (t/is (thrown? js/Error
                 (dwntf/handle-file-change (file-change-msg {:library-change "true"})))))

(defn- ^:async stale-share-file-delivery
  [first-topic]
  (let [save (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id session-id store requests requests$]}]
         (let [team-id     (uuid/next)
               page-id     (uuid/next)
               shape-id    (uuid/next)
               set-id      (uuid/next)
               local       (dch/commit {:file-id file-id :file-revn 0 :file-vern 0 :features #{}
                                        :redo-changes [(thp/rename page-id shape-id "Local edit")]
                                        :undo-changes [(thp/rename page-id shape-id "Original")]})
               token-edit  {:type :set-token-set :id set-id :attrs {:id set-id :name "Peer set"}}
               page-edit   {:type :mod-page :id page-id :name "Peer page"}
               common      {:file-id file-id :profile-id (uuid/next) :session-id (uuid/next)
                            :revn 1 :vern 0}
               ;; Only the file topic has the complete changes and row ID.
               file-msg    (assoc common :type :file-change :topic file-id :library-change true
                                  :changes [page-edit token-edit] :revision-ids [(uuid/next)])
               team-msg    (assoc common :type :library-change :topic team-id
                                  :modified-at (ct/now) :changes [token-edit])
               [first-msg second-msg] (if (= first-topic :file)
                                        [file-msg team-msg]
                                        [team-msg file-msg])
               effects     (atom [])
               seen        (->> (ptk/input-stream store)
                                (rx/filter library-effect?)
                                (rx/subs!
                                 (fn [event]
                                   (let [data (get-in @store [:files file-id :data])]
                                     (swap! effects conj [(ptk/type event)
                                                          (get-in data [:pages-index page-id :name])
                                                          (ctob/get-name (ctob/get-set (:tokens-lib data) set-id))])))))]
           (try
             (ptk/emit! store (thp/open-file file-id page-id (thp/file-data page-id shape-id "Original"))
                        #(-> %
                             (assoc-in [:files file-id :is-shared] false)
                             (assoc-in [:persistence :file-order file-id] (porder/loaded 0 0)))
                        (dwntf/initialize team-id file-id)
                        local)
             (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                          "the local edit is pending"))
             (ptk/emit! store (websocket-message first-msg))
             (if (= first-topic :team)
               (do
                 (await (async/settle))
                 (t/is (= "Page" (get-in @store [:files file-id :data :pages-index page-id :name])))
                 (t/is (empty? @effects) "the team copy cannot run effects on its own"))
               (await (async/wait-for-store store #(= 1 (get-in @store [:files file-id :revn]))
                                            "the full delta applies before the team copy arrives")))
             (ptk/emit! store (websocket-message second-msg))
             (await (async/wait-for-store store #(>= (count @effects) 3)
                                          "the installed file delta emits its library effects"))
             (ptk/emit! store (websocket-message file-msg)
                        (websocket-message (assoc file-msg :vern 1 :revn 7 :repaired true)))
             (await (async/settle))
             (t/is (= {::dwl/library-change-effects 1 ::dwl/notify-sync-file 1
                       ::dwtp/propagate-workspace-tokens 1}
                      (frequencies (map first @effects))))
             (t/is (every? #(= ["Peer page" "Peer set"] (subvec % 1)) @effects)
                   "effects only see complete, updated file data")
             (t/is (= 1 (count (get-in @store [:persistence :remote-changes file-id])))
                   "only the full delta is applied under the pending local edit")
             (t/is (= "Local edit" (thp/shape-name store file-id page-id shape-id)))
             (t/is (false? (get-in @store [:files file-id :is-shared]))
                   "the flag does not change the opening's share status")
             (t/is (empty? (get-file-reads requests)))

             (ptk/emit! store ::dps/force-persist)
             (await (async/wait-for #(some (fn [[cmd _]] (= cmd :update-file-v2)) @requests)
                                    "the save queue sends the pending local edit" {:signal requests$}))
             (let [[[_ params] :as saves] (filter #(= :update-file-v2 (first %)) @requests)]
               (t/is (= 1 (count saves)))
               (t/is (= 1 (:revn params))))
             (rx/push! save {:revn 1 :saved-revn 2
                             :lagged [{:id (uuid/next) :file-id file-id :session-id session-id :revn 2
                                       :changes (:redo-changes @local)}]})
             (await (async/wait-for-store store #(= :saved (get-in @store [:persistence :status]))
                                          "the local save settles without a reconciliation"))
             (t/is (empty? (get-file-reads requests)))
             (finally (rx/dispose! seen)))))
       (fn [command _]
         (->> (case command
                :update-file-v2 save
                :get-file-object-thumbnails (rx/of {})
                (rx/empty))
              (rx/observe-on :async)))))))

(t/deftest ^:async a-stale-private-tab-ignores-the-team-copy-before-the-marked-file-delta
  (await (stale-share-file-delivery :team)))

(t/deftest ^:async a-stale-private-tab-ignores-the-team-copy-after-the-marked-file-delta
  (await (stale-share-file-delivery :file)))

(t/deftest ^:async a-shared-open-file-applies-the-aggregate-only-from-the-file-topic
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store requests]}]
       (let [page-id       (uuid/next)
             shape-id      (uuid/next)
             color-id      (uuid/next)
             set-id        (uuid/next)
             original      (assoc-in (thp/file-data page-id shape-id "Original")
                                     [:colors color-id] (library-color color-id "Original color"))
             local         (dch/commit {:file-id file-id :file-revn 0 :file-vern 0
                                        :redo-changes [(thp/rename page-id shape-id "Local edit")]
                                        :undo-changes [(thp/rename page-id shape-id "Original")]})
             team-changes  [{:type :mod-color :color (library-color color-id "Peer color")}
                            {:type :set-token-set :id set-id :attrs {:id set-id :name "Peer set"}}
                            (thp/rename page-id shape-id "Repaired")
                            (thp/set-attr page-id shape-id :opacity 0.7)]
             library-msg   (library-change-msg {:file-id file-id :revn 2 :repair-base-revn 0
                                                :repaired true
                                                :changes team-changes})
             file-msg      (-> library-msg
                               (assoc :type :file-change :library-change true
                                      :revision-ids [(uuid/next) (uuid/next)])
                               (dissoc :modified-at)
                               (assoc :changes (into [{:type :mod-page :id page-id :name "Peer page"}]
                                                     team-changes)))]
         (ptk/emit! store (thp/open-file file-id page-id original)
                    #(assoc-in % [:files file-id :is-shared] true)
                    local)
         (await (async/wait-for-store store #(seq (get-in @store [:persistence :buffer]))
                                      "the local edit is pending"))

         ;; The library delivery comes first but has only the filtered delta.
         (ptk/emit! store (dwntf/handle-library-change library-msg))
         (await (async/settle))
         (t/is (= "Original color" (get-color-name store file-id color-id)))

         (ptk/emit! store (dwntf/handle-file-change file-msg))
         (await (async/wait-for-store store #(= 2 (get-in @store [:files file-id :revn]))
                                      "the full aggregate is installed"))
         (ptk/emit! store (dwntf/handle-library-change library-msg))
         (await (async/settle))
         (t/is (= "Peer page" (get-in @store [:files file-id :data :pages-index page-id :name])))
         (t/is (= "Peer color" (get-color-name store file-id color-id)))
         (t/is (= 0.7 (thp/shape-attr store file-id page-id shape-id :opacity)))
         (t/is (= "Local edit" (thp/shape-name store file-id page-id shape-id)))
         (t/is (= 1 (count (get-in @store [:persistence :remote-changes file-id])))
               "only one remote batch is retained under the pending edit")
         (t/is (= dps/file-repaired-tag (get-in @store [:notification :tag]))
               "the repair notice takes priority over the token-set update notice")
         (t/is (empty? (get-file-reads requests))))))))

(defn- library-data
  [file-id color]
  {:id file-id :pages [] :pages-index {} :colors {(:id color) color}})

(defn- load-linked-library
  [file-id library-id color synced-at]
  (let [library {:id library-id :library-of file-id :synced-at synced-at
                 :revn 6 :vern 0 :data (library-data library-id color)}]
    (fn [state]
      (-> state
          (assoc :workspace-load-id (uuid/next))
          (assoc-in [:files file-id :data] {:id file-id :pages [] :pages-index {}})
          (assoc-in [:files library-id] library)
          (dps/file-base-loaded library)))))

(defn- read-file-response
  [read]
  (fn [command _]
    (->> (case command
           :get-file read
           :get-file-object-thumbnails (rx/of {})
           (rx/empty))
         (rx/observe-on :async))))

(defn- height-set
  [name value]
  (ctob/make-token-set
   :name name
   :tokens {"size" (ctob/make-token :name "size" :type :sizing :value value)}))

(defn- token-status
  [sets]
  (ctos/make-tokens-status :active-set-ids (into #{} (map ctob/get-id) sets)))

(defn- ^:async marked-file-reconciliation
  [mark-first?]
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [page-id (uuid/next)
               set-id  (uuid/next)
               data    (assoc (thp/file-data page-id (uuid/next) "Original") :id file-id)
               msg     (file-change-msg {:file-id file-id :revn 2 :revision-ids [(uuid/next)]
                                         :changes [{:type :set-token-set :id set-id
                                                    :attrs {:id set-id :name "Delta set"}}]})
               read-set (height-set "Read set" "16")
               tokens  (ctob/add-set (ctob/make-tokens-lib) read-set)
               effects (atom [])
               sub     (->> (ptk/input-stream store)
                            (rx/filter library-effect?)
                            (rx/subs! (fn [event]
                                        (swap! effects conj
                                               [(ptk/type event)
                                                (get-in @store [:files file-id :data :tokens-lib])]))))]
           (try
             (ptk/emit! store (thp/open-file file-id page-id data)
                        #(-> %
                             (assoc-in [:files file-id :is-shared] false)
                             (assoc-in [:persistence :file-order file-id] (porder/loaded 0 0)))
                        (dwntf/handle-file-change (cond-> msg mark-first? (assoc :library-change true))))
             (await (async/wait-for #(seq (get-file-reads requests))
                                    "the full delta has a gap and requires a read" {:signal requests$}))
             (ptk/emit! store
                        (dwntf/handle-file-change
                         (cond-> (assoc msg :revn 3 :revision-ids [(uuid/next)])
                           (not mark-first?) (assoc :library-change true)))
                        (dwntf/handle-file-change
                         (assoc msg :revn 4 :revision-ids [(uuid/next)] :changes [])))
             (await (async/settle))
             (t/is (empty? @effects) "the skipped delta emits no library effects")
             (rx/push! read {:id file-id :revn 4 :vern 0 :is-shared false
                             :data (assoc data :tokens-lib tokens :tokens-status (token-status [read-set]))})
             (await (async/wait-for-store store #(>= (count @effects) 2)
                                          "the installed base emits its effects"))
             (await (async/settle))
             (t/is (= #{[::dwl/notify-sync-file tokens] [::dwtp/propagate-workspace-tokens tokens]}
                      (set @effects)))
             (t/is (= 2 (count @effects)) "the authoritative data only runs each effect once")
             (t/is (= [file-id] (get-file-reads requests)))
             (t/is (nil? (:notification @store)) "a read cannot identify which token sets or themes changed")
             (finally (rx/dispose! sub)))))
       (read-file-response read)))))

(t/deftest ^:async a-marked-delta-reconciles-the-open-file-despite-stale-share-status
  (await (marked-file-reconciliation true)))

(t/deftest ^:async a-marked-delta-joins-an-in-flight-read-without-discarding-its-effects
  (await (marked-file-reconciliation false)))

(defn- ^:async with-preview-reconciliation
  [scenario & {:keys [tokens-source]}]
  (let [history (rx/subject)
        read    (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$] :as fixture}]
         (let [team-id     (uuid/next)
               page-id     (uuid/next)
               shape-id    (uuid/next)
               snapshot-id (uuid/next)
               live-set    (height-set "Live set" "24")
               ref-set     (height-set "Reference set" "8")
               ref-id      (ctob/get-id ref-set)
               base-tokens (reduce ctob/add-set (ctob/make-tokens-lib) [live-set ref-set])
               token-id    (:id (ctob/get-token-by-name base-tokens "Reference set" "size"))
               peer-tokens (ctob/update-token base-tokens ref-id token-id #(assoc % :value "16"))
               status      (token-status [live-set ref-set])
               data        (cond-> (assoc (thp/file-data page-id shape-id "Live")
                                          :id file-id :tokens-lib base-tokens :tokens-status status)
                             tokens-source (assoc :tokens-source tokens-source))
               historical  (assoc (thp/file-data page-id shape-id "Historical") :id file-id)
               effects     (atom [])
               sub         (->> (ptk/input-stream store)
                                (rx/filter library-effect?)
                                (rx/subs!
                                 (fn [event]
                                   (let [file (get-in @store [:files file-id])]
                                     (swap! effects conj
                                            [(ptk/type event)
                                             (:revn file)
                                             (datafy (get-in file [:data :tokens-lib]))
                                             (thp/shape-name store file-id page-id shape-id)])))))]
           (try
             (ptk/emit! store (thp/open-file file-id page-id data)
                        #(-> %
                             (assoc :workspace-load-id (uuid/next))
                             (update-in [:files file-id] assoc :vern 0 :is-shared false))
                        (dwntf/initialize team-id file-id)
                        (dwv/enter-preview snapshot-id))
             (await (async/wait-for #(some (comp #{:get-file-snapshot} first) @requests)
                                    "the historical version is requested" {:signal requests$}))
             (rx/push! history {:id file-id :vern 0 :revn 0 :data historical})
             (await (async/wait-for-store store #(= "Historical" (thp/shape-name store file-id page-id shape-id))
                                          "the historical version is visible"))
             (await (scenario (assoc fixture
                                     :read read :data data :page-id page-id :shape-id shape-id
                                     :base-tokens base-tokens :peer-tokens peer-tokens :effects effects
                                     :ref-id ref-id :token-id token-id :live-set live-set)))
             (finally (rx/dispose! sub)))))
       (fn [command _]
         (->> (case command
                :get-file-snapshot history
                :get-file read
                (rx/empty))
              (rx/take 1)
              (rx/observe-on :async)))))))

(defn- ^:async request-preview-file
  [{:keys [file-id store requests requests$]} revn library-change]
  (let [expected (conj (get-file-reads requests) file-id)
        message  (file-change-msg (cond-> {:file-id file-id :topic file-id :revn revn
                                           :revision-ids [(uuid/next)]}
                                    library-change (assoc :library-change true)))]
    (ptk/emit! store (websocket-message message))
    (await (async/wait-for #(= expected (get-file-reads requests))
                           "the WebSocket delta requires a fresh live file" {:signal requests$}))))

(defn- ^:async reconcile-preview-file
  [{:keys [file-id store read data] :as fixture} revn library-change tokens
   & {:keys [shared tokens-status] :or {shared false}}]
  (await (request-preview-file fixture revn library-change))
  (rx/push! read {:id file-id :vern 0 :revn revn :is-shared shared
                  :data (cond-> (assoc data :tokens-lib tokens)
                          tokens-status (assoc :tokens-status tokens-status))})
  (await (async/wait-for-store store #(= revn (get-in @store [:workspace-versions :backup :revn]))
                               "the result is installed in the live backup")))

(defn- ^:async leave-preview
  [store]
  (ptk/emit! store (dwv/exit-preview))
  (await (async/wait-for-store store #(nil? (get-in @store [:workspace-global :preview-id]))
                               "the preview ends"))
  (await (async/settle)))

(t/deftest ^:async preview-defers-marked-library-effects-until-the-last-live-file-is-visible
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [file-id store page-id shape-id peer-tokens effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 true peer-tokens))
       ;; An unmarked later read must not lose the first read's effects.
       (await (reconcile-preview-file fixture 3 false (ctob/make-tokens-lib (datafy peer-tokens))))
       (await (async/settle))
       (t/is (= "Historical" (thp/shape-name store file-id page-id shape-id)))
       (t/is (nil? (get-in @store [:files file-id :data :tokens-lib])))
       (t/is (empty? @effects) "no library effects run against the snapshot")
       (await (leave-preview store))
       (let [live [3 (datafy peer-tokens) "Live"]]
         (t/is (= #{(into [::dwl/notify-sync-file] live)
                    (into [::dwtp/propagate-workspace-tokens] live)}
                  (set @effects)))
         (t/is (= 2 (count @effects)) "each effect runs once on the latest live data"))))))

(t/deftest ^:async preview-does-not-propagate-tokens-that-return-to-their-original-values
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store base-tokens peer-tokens ref-id token-id effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 true peer-tokens))
       (let [restored (with-redefs [ct/now (constantly (ct/inst 0))]
                        (ctob/update-token peer-tokens ref-id token-id #(assoc % :value "8")))]
         (t/is (= "8" (:value (ctob/get-token restored ref-id token-id))))
         (t/is (not= (:modified-at (ctob/get-token base-tokens ref-id token-id))
                     (:modified-at (ctob/get-token restored ref-id token-id))))
         (t/is (not= (ctob/get-modified-at (ctob/get-set base-tokens ref-id))
                     (ctob/get-modified-at (ctob/get-set restored ref-id))))
         (await (reconcile-preview-file fixture 3 true restored))
         (await (leave-preview store))
         (t/is (= [[::dwl/notify-sync-file 3 (datafy restored) "Live"]] @effects)))))))

(t/deftest ^:async preview-propagates-after-a-read-that-only-reorders-token-sets
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [file-id store data base-tokens effects] :as fixture}]
       (let [reordered (reduce ctob/add-set
                               (ctob/make-tokens-lib (dissoc (datafy base-tokens) :sets))
                               (reverse (ctob/get-sets base-tokens)))
             status    (:tokens-status data)]
         (t/is (= (datafy base-tokens) (datafy reordered)) "the set contents have not changed")
         (t/is (= "8" (get-in (cfo/get-tokens-in-active-sets status base-tokens) ["size" :value])))
         (t/is (= "24" (get-in (cfo/get-tokens-in-active-sets status reordered) ["size" :value])))
         (t/is (= ["Live set" "Reference set"] (ctob/get-set-names base-tokens)))
         (await (reconcile-preview-file fixture 2 true reordered))
         (await (leave-preview store))
         (t/is (= ["Reference set" "Live set"]
                  (ctob/get-set-names (get-in @store [:files file-id :data :tokens-lib]))))
         (t/is (= [[::dwtp/propagate-workspace-tokens 2 (datafy reordered) "Live"]]
                  (filterv #(= ::dwtp/propagate-workspace-tokens (first %)) @effects))))))))

(t/deftest ^:async preview-propagates-after-a-read-that-only-changes-active-sets
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [file-id store base-tokens live-set effects] :as fixture}]
       (let [status (token-status [live-set])]
         (await (reconcile-preview-file fixture 2 true base-tokens :tokens-status status))
         (await (leave-preview store))
         (t/is (= "24" (get-in (cfo/get-tokens-in-active-sets
                                (get-in @store [:files file-id :data :tokens-status]) base-tokens)
                               ["size" :value])))
         (t/is (= [::dwl/notify-sync-file ::dwtp/propagate-workspace-tokens]
                  (mapv first @effects))))))))

(t/deftest ^:async preview-notifies-without-propagating-after-library-metadata-changes
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store base-tokens ref-id effects] :as fixture}]
       (let [updated (-> base-tokens
                         (ctob/update-set ref-id #(ctob/set-description % "Reference sizes"))
                         (ctob/update-theme ctob/hidden-theme-id #(assoc % :description "Active sets")))]
         (t/is (not= (datafy base-tokens) (datafy updated)))
         (await (reconcile-preview-file fixture 2 true updated))
         (await (leave-preview store))
         (t/is (= [[::dwl/notify-sync-file 2 (datafy updated) "Live"]] @effects)))))))

(t/deftest ^:async preview-notifies-without-propagating-after-an-inactive-set-is-added
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store base-tokens effects] :as fixture}]
       (let [updated (ctob/add-set base-tokens (height-set "Inactive set" "32"))]
         (await (reconcile-preview-file fixture 2 true updated))
         (await (leave-preview store))
         (t/is (= [[::dwl/notify-sync-file 2 (datafy updated) "Live"]] @effects)))))))

(t/deftest ^:async preview-propagates-after-a-read-that-changes-a-token-reference
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store base-tokens ref-id token-id effects] :as fixture}]
       (let [updated (ctob/update-token base-tokens ref-id token-id #(assoc % :value "{other-size}"))]
         (await (reconcile-preview-file fixture 2 true updated))
         (await (leave-preview store))
         (t/is (= [::dwl/notify-sync-file ::dwtp/propagate-workspace-tokens]
                  (mapv first @effects))))))))

(t/deftest ^:async preview-uses-the-share-status-of-the-reconciled-file
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store peer-tokens effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 false peer-tokens :shared true))
       (await (leave-preview store))
       (t/is (= [[::dwl/notify-sync-file 2 (datafy peer-tokens) "Live"]
                 [::dwtp/propagate-workspace-tokens 2 (datafy peer-tokens) "Live"]]
                @effects))))))

(t/deftest ^:async preview-does-not-propagate-tokens-from-an-inactive-source
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store peer-tokens effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 true peer-tokens))
       (await (leave-preview store))
       (t/is (= [[::dwl/notify-sync-file 2 (datafy peer-tokens) "Live"]] @effects)))
     :tokens-source (uuid/next))))

(t/deftest ^:async preview-library-effects-do-not-cross-workspace-openings
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [store peer-tokens effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 true peer-tokens))
       (ptk/emit! store #(assoc % :workspace-load-id (uuid/next)))
       (await (leave-preview store))
       (t/is (empty? @effects))))))

(t/deftest ^:async preview-library-effects-do-not-cross-file-versions
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [file-id store peer-tokens effects] :as fixture}]
       (await (reconcile-preview-file fixture 2 true peer-tokens))
       ;; Simulate a restored live version without also changing the opening,
       ;; to isolate the version guard at delivery.
       (ptk/emit! store #(assoc-in % [:workspace-versions :backup :vern] 1))
       (await (leave-preview store))
       (t/is (= 1 (get-in @store [:files file-id :vern])))
       (t/is (empty? @effects))))))

(t/deftest ^:async exiting-preview-before-the-read-uses-normal-reconciliation
  (await
   (with-preview-reconciliation
     (^:async fn [{:keys [file-id store read data peer-tokens effects] :as fixture}]
       (await (request-preview-file fixture 2 true))
       (await (leave-preview store))
       (t/is (empty? @effects) "leaving the preview does not run effects for an in-flight read")
       (rx/push! read {:id file-id :vern 0 :revn 2 :is-shared false :data (assoc data :tokens-lib peer-tokens)})
       (await (async/wait-for-store store #(= 2 (get-in @store [:files file-id :revn]))
                                    "the read is installed in the live file"))
       (await (async/settle))
       (t/is (= [[::dwl/notify-sync-file 2 (datafy peer-tokens) "Live"]
                 [::dwtp/propagate-workspace-tokens 2 (datafy peer-tokens) "Live"]]
                @effects))))))

(t/deftest ^:async an-in-flight-read-ignores-marked-duplicates-and-other-versions
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [page-id   (uuid/next)
               data      (assoc (thp/file-data page-id (uuid/next) "Original") :id file-id)
               msg       (file-change-msg {:file-id file-id :vern 1 :revision-ids [(uuid/next)]
                                           :library-change true
                                           :changes [{:type :add-color
                                                      :color (library-color (uuid/next) "Peer color")}]})
               gap       (-> msg
                             (dissoc :library-change)
                             (assoc :revn 3 :changes [] :revision-ids [(uuid/next)]))
               stale     (assoc msg :vern 0 :revn 5 :repaired true :revision-ids [(uuid/next)])
               tokens    (-> (ctob/make-tokens-lib)
                             (ctob/add-set (ctob/make-token-set {:id (uuid/next) :name "Read set"})))
               effects   (atom [])
               sub       (->> (ptk/input-stream store)
                              (rx/filter library-effect?)
                              (rx/subs! #(swap! effects conj (ptk/type %))))]
           (try
             (ptk/emit! store (thp/open-file file-id page-id data)
                        #(-> %
                             (update-in [:files file-id] assoc :vern 1 :is-shared true)
                             (assoc-in [:persistence :file-order file-id] (porder/loaded 1 0)))
                        (dwntf/handle-file-change msg))
             (await (async/wait-for-store store #(= 2 (count @effects))
                                          "the marked delta applies once"))
             (ptk/emit! store (dwntf/handle-file-change gap))
             (await (async/wait-for #(seq (get-file-reads requests))
                                    "the unmarked delta requires a read" {:signal requests$}))
             (ptk/emit! store (dwntf/handle-file-change msg) (dwntf/handle-file-change stale))
             (await (async/settle))
             (rx/push! read {:id file-id :revn 3 :vern 1 :is-shared false :data (assoc data :tokens-lib tokens)})
             (await (async/wait-for-store store #(= 3 (get-in @store [:files file-id :revn]))
                                          "the obsolete revision cannot raise the minimum for the read"))
             (await (async/settle))
             (t/is (= {::dwl/library-change-effects 1 ::dwl/notify-sync-file 1} (frequencies @effects))
                   "neither discarded message can add its effects to the read")
             (t/is (nil? (:notification @store)))
             (t/is (= [file-id] (get-file-reads requests)))
             (finally (rx/dispose! sub)))))
       (read-file-response read)))))

(t/deftest ^:async ordinary-file-changes-that-arrive-out-of-order-read-the-current-file
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [page-id  (uuid/next)
               shape-id (uuid/next)
               data     (thp/file-data page-id shape-id "Original")
               rename   (fn [revn name]
                          (file-change-msg {:file-id file-id :revn revn :revision-ids [(uuid/next)]
                                            :changes [(thp/rename page-id shape-id name)]}))
               contiguous (rename 1 "Contiguous peer")
               older    (rename 2 "Earlier peer")
               newer    (rename 3 "Later peer")
               name     #(thp/shape-name store file-id page-id shape-id)]
           (ptk/emit! store (thp/open-file file-id page-id data)
                      #(dps/file-base-loaded % {:id file-id :vern 0 :revn 0})
                      (dwntf/handle-file-change contiguous))
           (await (async/wait-for-store store #(= "Contiguous peer" (name)) "the contiguous edit applies"))
           (ptk/emit! store (dwntf/handle-file-change contiguous))
           (await (async/settle))
           (t/is (empty? (get-file-reads requests)))
           (ptk/emit! store (dwntf/handle-file-change newer) (dwntf/handle-file-change older))
           (t/is (= "Contiguous peer" (name)) "the deltas cannot overwrite the view out of order")
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the gap requests current data" {:signal requests$}))
           (rx/push! read {:id file-id :vern 0 :revn 3
                           :data (thp/file-data page-id shape-id "Later peer")})
           (await (async/wait-for-store store #(= "Later peer" (name)) "the server state is installed"))
           (ptk/emit! store (dwntf/handle-file-change older) (dwntf/handle-file-change newer))
           (await (async/settle))
           (t/is (= "Later peer" (name)))
           (t/is (= [file-id] (get-file-reads requests)) "the read also covers both replays")
           (t/is (not-any? (comp #{:update-file-v2} first) @requests))))
       (read-file-response read)))))

(t/deftest ^:async an-ordinary-library-change-that-arrives-late-reads-both-assets
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [library-id (uuid/next)
               first-id   (uuid/next)
               second-id  (uuid/next)
               older      (library-color first-id "Earlier peer")
               newer      (library-color second-id "Later peer")
               msg        (library-change-msg {:file-id library-id})
               change     (fn [revn color]
                            (dwntf/handle-library-change
                             (assoc msg :revn revn :changes [{:type :add-color :color color}])))]
           (ptk/emit! store
                      (load-linked-library file-id library-id (library-color (uuid/next) "Original")
                                           (:modified-at msg))
                      (change 9 newer))
           (await (async/wait-for-store store #(= "Later peer" (get-color-name store library-id second-id))
                                        "a forward gap on the team topic is valid"))
           (t/is (empty? (get-file-reads requests)))
           (ptk/emit! store (change 8 older))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the unseen older asset requires a read" {:signal requests$}))
           (rx/push! read {:id library-id :vern 0 :revn 9
                           :data (assoc-in (library-data library-id newer) [:colors first-id] older)})
           (await (async/wait-for-store store #(= "Earlier peer" (get-color-name store library-id first-id))
                                        "the read installs the missing asset"))
           (t/is (= "Later peer" (get-color-name store library-id second-id)))
           (t/is (= [library-id] (get-file-reads requests)))
           (t/is (not-any? (comp #{:update-file-v2} first) @requests))))
       (read-file-response read)))))

(defn- get-fallback-scenario
  [file-id]
  (let [page-id  (uuid/next)
        shape-id (uuid/next)
        child-id (uuid/next)
        child    (cts/setup-shape {:id child-id :type :rect :name "New"
                                   :parent-id uuid/zero :frame-id uuid/zero})
        original (thp/file-data page-id shape-id "Original")
        edit     {:type :add-obj :id child-id :page-id page-id :obj child
                  :parent-id uuid/zero :frame-id uuid/zero}
        repair   [(thp/set-attr page-id uuid/zero :shapes [shape-id])
                  (thp/rename page-id shape-id "Repaired")]
        common   {:file-id file-id :profile-id (uuid/next) :topic file-id}]
    {:page-id page-id :shape-id shape-id :children [shape-id child-id]
     :open (thp/open-file file-id page-id original)
     :file {:id file-id :revn 2 :vern 0
            :data (-> (thp/add-shape original page-id child)
                      (assoc-in [:pages-index page-id :objects shape-id :name] "Repaired"))}
     :repair-msg (file-change-msg (assoc common :revn 1 :changes repair :repaired true
                                         :session-id #uuid "a11c0de5-0000-4000-8000-000000000001"
                                         :revision-ids [(uuid/next)]))
     :edit-msg (file-change-msg (assoc common :revn 2 :changes [edit] :revision-ids [(uuid/next)]))}))

(t/deftest ^:async a-late-fallback-repair-does-not-drop-a-child-added-by-the-edit
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [{:keys [page-id shape-id children open file repair-msg edit-msg]} (get-fallback-scenario file-id)]
           (ptk/emit! store open (dwntf/handle-file-change edit-msg))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the edit ahead of the repair requests the current file" {:signal requests$}))
           (ptk/emit! store (dwntf/handle-file-change repair-msg))
           (t/is (= [shape-id] (thp/shape-attr store file-id page-id uuid/zero :shapes))
                 "neither delta is installed on an unproven base")
           (t/is (= "Original" (thp/shape-name store file-id page-id shape-id)))
           (rx/push! read file)
           (await (async/wait-for-store store #(= "Repaired" (thp/shape-name store file-id page-id shape-id))
                                        "the current server state is installed"))
           (t/is (= children (thp/shape-attr store file-id page-id uuid/zero :shapes)))
           (t/is (= dps/file-repaired-tag (get-in @store [:notification :tag])))
           (ptk/emit! store (ntf/hide :tag dps/file-repaired-tag)
                      (dwntf/handle-file-change repair-msg))
           (await (async/settle))
           (t/is (nil? (:notification @store)) "the read already announced this repair")
           (t/is (= [file-id] (get-file-reads requests)))))
       (read-file-response read)))))

(t/deftest ^:async a-repair-covered-by-a-recovery-read-still-notifies-once
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [page-id (uuid/next)
               shape-id (uuid/next)
               data    (thp/file-data page-id shape-id "Original")
               message #(dwntf/handle-file-change (file-change-msg (assoc % :file-id file-id)))
               repair  {:revn 4 :repaired true :repair-base-revn 2
                        :revision-ids [(uuid/next) (uuid/next)]
                        :changes [(thp/rename page-id shape-id "Repaired")]}
               later   {:revn 5 :revision-ids [(uuid/next)]
                        :changes [(thp/rename page-id shape-id "Later peer")]}]
           (ptk/emit! store (thp/open-file file-id page-id data)
                      #(-> %
                           (assoc :workspace-load-id (uuid/next))
                           (assoc-in [:files file-id :revn] 2)
                           (dps/file-base-loaded {:id file-id :vern 0 :revn 2}))
                      (message later))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the gap starts a recovery read" {:signal requests$}))
           (rx/push! read {:id file-id :vern 0 :revn 5
                           :data (thp/file-data page-id shape-id "Later peer")})
           (await (async/wait-for-store store #(= "Later peer" (thp/shape-name store file-id page-id shape-id))
                                        "the read installs the newer value"))
           (ptk/emit! store (message (assoc repair :revn 2 :repair-base-revn 0))
                      (message (assoc repair :vern 1)))
           (await (async/settle))
           (t/is (nil? (:notification @store)) "initially covered and other-version repairs stay silent")
           (ptk/emit! store (message repair))
           (await (async/settle))
           (t/is (= dps/file-repaired-tag (get-in @store [:notification :tag]))
                 "the late repair still notifies")
           (ptk/emit! store (ntf/hide :tag dps/file-repaired-tag) (message repair))
           (await (async/settle))
           (t/is (nil? (:notification @store)) "the same repair does not show again after dismissal")
           (t/is (= "Later peer" (thp/shape-name store file-id page-id shape-id)))
           (t/is (= [file-id] (get-file-reads requests)))
           (t/is (not-any? (comp #{:update-file-v2} first) @requests))))
       (read-file-response read)))))

(t/deftest ^:async contiguous-fallback-rows-apply-in-order-and-a-known-repair-is-ignored
  (await
   (thp/with-persistence
     (^:async fn [{:keys [file-id store requests]}]
       (let [{:keys [page-id shape-id children open repair-msg edit-msg]} (get-fallback-scenario file-id)
             notices (atom 0)
             sub     (->> (ptk/input-stream store)
                          (rx/filter (ptk/type? ::dps/notify-repaired))
                          (rx/subs! (fn [_] (swap! notices inc))))]
         (try
           (ptk/emit! store open (dwntf/handle-file-change repair-msg))
           (await (async/wait-for-store store #(= 1 @notices) "the repair applies and notifies"))
           (t/is (= "Repaired" (thp/shape-name store file-id page-id shape-id)))
           (ptk/emit! store (dwntf/handle-file-change edit-msg))
           (await (async/wait-for-store store #(= 2 (get-in @store [:files file-id :revn]))
                                        "the edit applies after the repair"))
           (ptk/emit! store (dwntf/handle-file-change repair-msg))
           (await (async/settle))
           (t/is (= children (thp/shape-attr store file-id page-id uuid/zero :shapes)))
           (t/is (= 1 @notices))
           (t/is (empty? (get-file-reads requests)))
           (finally (rx/dispose! sub))))))))

(t/deftest a-file-repair-needs-more-than-the-latest-revision-number
  (let [message {:revn 3 :repaired true :revision-ids [(uuid/next)]}
        base    (porder/loaded 0 0)
        gap     (porder/applied base {:revn 2 :revision-ids [(uuid/next)]})]
    (t/is (= :reconcile (porder/decision base message false)) "the predecessor has not arrived")
    (t/is (= :reconcile (porder/decision gap message false)) "the predecessor has an earlier gap")))

(t/deftest recovery-keeps-a-bounded-history-of-announced-repairs
  (let [repairs (mapv (fn [revn] [revn (uuid/next)]) (range 1 140))
        order   (-> (porder/loaded 0 0)
                    (porder/announce-repairs repairs)
                    (porder/recovered 200))]
    (t/is (< (count (:announced-repairs order)) (count repairs)))
    (t/is (porder/announced-repair? order (first repairs)) "older duplicates stay retired")
    (t/is (porder/announced-repair? order (peek repairs)) "recent repairs keep their row identity")
    (t/is (not (porder/announced-repair? order [139 (uuid/next)])))
    (t/is (porder/recovered-repair? order [150 (uuid/next)]))))

(t/deftest ^:async a-library-gap-is-valid-but-a-late-aggregate-reads-the-current-library
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [library-id (uuid/next)
               color-id   (uuid/next)
               newest    (library-color color-id "Later peer")
               msg       (library-change-msg {:file-id library-id})]
           (ptk/emit! store (load-linked-library file-id library-id (library-color color-id "Original")
                                                 (:modified-at msg))
                      (dwntf/handle-library-change
                       (assoc msg :revn 9 :revision-ids [(uuid/next)]
                              :changes [{:type :mod-color :color newest}])))
           (await (async/wait-for-store store #(= "Later peer" (get-color-name store library-id color-id))
                                        "the library topic may skip unreported revisions"))
           (t/is (empty? (get-file-reads requests)))

           (ptk/emit! store
                      (dwntf/handle-library-change
                       (assoc msg :revn 8 :repair-base-revn 6 :repaired true
                              :revision-ids [(uuid/next) (uuid/next)]
                              :changes [{:type :mod-color :color (library-color color-id "Older repair")}])))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the late aggregate requests a fresh library" {:signal requests$}))
           (t/is (= "Later peer" (get-color-name store library-id color-id))
                 "the repair is not applied while the read is in flight")

           (rx/push! read {:id library-id :vern 0 :revn 10 :data (library-data library-id newest)})
           (await (async/wait-for-store store #(= 10 (get-in @store [:files library-id :revn]))
                                        "the fresh library is installed"))
           (t/is (= "Later peer" (get-color-name store library-id color-id)))
           (t/is (= [library-id] (get-file-reads requests)))))
       (read-file-response read)))))

(t/deftest ^:async library-fallback-repairs-allow-forward-gaps-but-not-late-rows
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [library-id (uuid/next)
               color-id   (uuid/next)
               newest     (library-color color-id "Later peer")
               msg        (library-change-msg {:file-id library-id})]
           (ptk/emit! store (load-linked-library file-id library-id (library-color color-id "Original")
                                                 (:modified-at msg))
                      (dwntf/handle-library-change
                       (assoc msg :revn 8 :repaired true
                              :changes [{:type :mod-color :color (library-color color-id "First repair")}])))
           (await (async/wait-for-store store #(= "First repair" (get-color-name store library-id color-id))
                                        "a filtered library may miss earlier revisions"))
           (ptk/emit! store
                      (dwntf/handle-library-change
                       (assoc msg :revn 10 :changes [{:type :mod-color :color newest}])))
           (await (async/wait-for-store store #(= "Later peer" (get-color-name store library-id color-id))
                                        "a normal library gap needs no read, even after a repair"))
           (t/is (empty? (get-file-reads requests)))

           (ptk/emit! store
                      (dwntf/handle-library-change
                       (assoc msg :revn 9 :repaired true
                              :changes [{:type :mod-color :color (library-color color-id "Late repair")}])))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "a late repair requests the current library" {:signal requests$}))
           (t/is (= "Later peer" (get-color-name store library-id color-id)))
           (rx/push! read {:id library-id :vern 0 :revn 11 :data (library-data library-id newest)})
           (await (async/wait-for-store store #(= 11 (get-in @store [:files library-id :revn]))
                                        "the updated library data is installed"))
           (t/is (= "Later peer" (get-color-name store library-id color-id)))
           (t/is (= [library-id] (get-file-reads requests)))))
       (read-file-response read)))))

(t/deftest ^:async an-older-ordinary-library-change-cannot-overwrite-an-applied-aggregate
  (let [read (rx/subject)]
    (await
     (thp/with-persistence
       (^:async fn [{:keys [file-id store requests requests$]}]
         (let [library-id (uuid/next)
               color-id   (uuid/next)
               repaired   (library-color color-id "Repaired")
               msg        (library-change-msg {:file-id library-id})]
           (ptk/emit! store (load-linked-library file-id library-id (library-color color-id "Original")
                                                 (:modified-at msg))
                      (dwntf/handle-library-change
                       (assoc msg :revn 9 :repair-base-revn 7 :repaired true
                              :revision-ids [(uuid/next) (uuid/next)]
                              :changes [{:type :mod-color :color repaired}])))
           (await (async/wait-for-store store #(= "Repaired" (get-color-name store library-id color-id))
                                        "the aggregate applies after a gap in the library topic"))
           (t/is (empty? (get-file-reads requests)))

           ;; Revision 7 was not part of the aggregate, and was unseen.
           (ptk/emit! store
                      (dwntf/handle-library-change
                       (assoc msg :revn 7 :revision-ids [(uuid/next)]
                              :changes [{:type :mod-color :color (library-color color-id "Older peer")}])))
           (await (async/wait-for #(seq (get-file-reads requests))
                                  "the older delta is reconciled without applying it" {:signal requests$}))
           (t/is (= "Repaired" (get-color-name store library-id color-id)))

           (rx/push! read {:id library-id :vern 0 :revn 10 :data (library-data library-id repaired)})
           (await (async/wait-for-store store #(= 10 (get-in @store [:files library-id :revn]))
                                        "the reconciliation preserves the repair"))
           (t/is (= "Repaired" (get-color-name store library-id color-id)))
           (t/is (= [library-id] (get-file-reads requests)))))
       (read-file-response read)))))

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
    (t/is (= created-at (:date props)))
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
