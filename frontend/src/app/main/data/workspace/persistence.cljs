;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.persistence
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes-spec :as pcs]
   [app.common.spec :as us]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.fonts :as df]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [potok.core :as ptk]))

(log/set-level! :info)

(declare persist-changes)
(declare persist-synchronous-changes)
(declare shapes-changes-persisted)
(declare update-persistence-status)

;; --- Persistence

(defn initialize-file-persistence
  [file-id]
  (ptk/reify ::initialize-persistence
    ptk/WatchEvent
    (watch [_ _ stream]
      (log/debug :hint "initialize persistence")
      (let [stoper   (rx/filter #(= ::finalize %) stream)
            commits  (l/atom [])

            local-file?
            #(as-> (:file-id %) event-file-id
               (or (nil? event-file-id)
                   (= event-file-id file-id)))

            library-file?
            #(as-> (:file-id %) event-file-id
               (and (some? event-file-id)
                    (not= event-file-id file-id)))

            on-dirty
            (fn []
              ;; Enable reload stoper
              (swap! st/ongoing-tasks conj :workspace-change)
              (st/emit! (update-persistence-status {:status :pending})))

            on-saving
            (fn []
              (st/emit! (update-persistence-status {:status :saving})))

            on-saved
            (fn []
              ;; Disable reload stoper
              (swap! st/ongoing-tasks disj :workspace-change)
              (st/emit! (update-persistence-status {:status :saved})))]

        (rx/merge
         (->> stream
              (rx/filter dch/commit-changes?)
              (rx/map deref)
              (rx/filter local-file?)
              (rx/tap on-dirty)
              (rx/filter (complement empty?))
              (rx/map (fn [commit]
                        (-> commit
                            (assoc :id (uuid/next))
                            (assoc :file-id file-id))))
              (rx/observe-on :async)
              (rx/tap #(swap! commits conj %))
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: changes watcher"))))

         (->> (rx/from-atom commits)
              (rx/filter (complement empty?))
              (rx/sample-when (rx/merge
                               (rx/interval 5000)
                               (rx/filter #(= ::force-persist %) stream)
                               (->> (rx/from-atom commits)
                                    (rx/filter (complement empty?))
                                    (rx/debounce 2000))))
              (rx/tap #(reset! commits []))
              (rx/tap on-saving)
              (rx/mapcat (fn [changes]
                           ;; NOTE: this is needed for don't start the
                           ;; next persistence before this one is
                           ;; finished.
                           (rx/merge
                            (rx/of (persist-changes file-id changes))
                            (->> stream
                                 (rx/filter (ptk/type? ::changes-persisted))
                                 (rx/take 1)
                                 (rx/tap on-saved)
                                 (rx/ignore)))))
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: save loop"))))

         ;; Synchronous changes
         (->> stream
              (rx/filter dch/commit-changes?)
              (rx/map deref)
              (rx/filter library-file?)
              (rx/filter (complement #(empty? (:changes %))))
              (rx/map persist-synchronous-changes)
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: synchronous save loop")))))))))

(defn persist-changes
  [file-id changes]
  (log/debug :hint "persist changes" :changes (count changes))
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2 (features/active-feature? state :components-v2)
            sid           (:session-id state)
            file          (get state :workspace-file)
            params        {:id (:id file)
                           :revn (:revn file)
                           :session-id sid
                           :changes-with-metadata (into [] changes)
                           :components-v2 components-v2}]

        (when (= file-id (:id params))
          (->> (rp/mutation :update-file params)
               (rx/mapcat (fn [lagged]
                            (log/debug :hint "changes persisted" :lagged (count lagged))
                            (let [lagged (cond->> lagged
                                           (= #{sid} (into #{} (map :session-id) lagged))
                                           (map #(assoc % :changes [])))

                                  frame-updates
                                  (-> (group-by :page-id changes)
                                      (d/update-vals #(into #{} (mapcat :frames) %)))]

                              (rx/merge
                               (->> (rx/from frame-updates)
                                    (rx/flat-map (fn [[page-id frames]]
                                              (->> frames (map #(vector page-id %)))))
                                    (rx/map (fn [[page-id frame-id]] (dwt/update-thumbnail (:id file) page-id frame-id))))
                               (->> (rx/of lagged)
                                    (rx/mapcat seq)
                                    (rx/map #(shapes-changes-persisted file-id %)))))))
               (rx/catch (fn [cause]
                           (rx/concat
                            (if (= :authentication (:type cause))
                              (rx/empty)
                              (rx/of (rt/assign-exception cause)))
                            (rx/throw cause))))))))))


(defn persist-synchronous-changes
  [{:keys [file-id changes]}]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-synchronous-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [components-v2 (features/active-feature? state :components-v2)
            sid     (:session-id state)
            file    (get-in state [:workspace-libraries file-id])

            params  {:id (:id file)
                     :revn (:revn file)
                     :session-id sid
                     :changes changes
                     :components-v2 components-v2}]

        (when (:id params)
          (->> (rp/mutation :update-file params)
               (rx/ignore)))))))


(defn update-persistence-status
  [{:keys [status reason]}]
  (ptk/reify ::update-persistence-status
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-persistence
              (fn [local]
                (assoc local
                       :reason reason
                       :status status
                       :updated-at (dt/now)))))))

(s/def ::shapes-changes-persisted
  (s/keys :req-un [::revn ::pcs/changes]))

(defn shapes-persisted-event? [event]
  (= (ptk/type event) ::changes-persisted))

(defn shapes-changes-persisted
  [file-id {:keys [revn changes] :as params}]
  (us/verify ::us/uuid file-id)
  (us/verify ::shapes-changes-persisted params)
  (ptk/reify ::changes-persisted
    ptk/UpdateEvent
    (update [_ state]
      (if (= file-id (:current-file-id state))
        (-> state
            (update-in [:workspace-file :revn] max revn)
            (update :workspace-data cp/process-changes changes))
        (-> state
            (update-in [:workspace-libraries file-id :revn] max revn)
            (update-in [:workspace-libraries file-id :data]
                       cp/process-changes changes))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetching & Uploading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::name string?)
(s/def ::type keyword?)
(s/def ::file-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/integer)
(s/def ::revn ::us/integer)
(s/def ::ordering ::us/integer)
(s/def ::data ::ctf/data)

(s/def ::file ::dd/file)
(s/def ::project ::dd/project)
(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::file-id
                   ::revn
                   ::created-at
                   ::modified-at
                   ::ordering
                   ::data]))

(declare fetch-libraries-content)
(declare bundle-fetched)

(defn fetch-bundle
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)
            components-v2 (features/active-feature? state :components-v2)]
        (->> (rx/zip (rp/query! :file-raw {:id file-id :components-v2 components-v2})
                     (rp/query! :team-users {:file-id file-id})
                     (rp/query! :project {:id project-id})
                     (rp/query! :file-libraries {:file-id file-id})
                     (rp/cmd! :get-profiles-for-file-comments {:file-id file-id :share-id share-id}))
             (rx/take 1)
             (rx/map (fn [[file-raw users project libraries file-comments-users]]
                       {:file-raw file-raw
                        :users users
                        :project project
                        :libraries libraries
                        :file-comments-users file-comments-users}))
             (rx/mapcat (fn [{:keys [project] :as bundle}]
                          (rx/of (ptk/data-event ::bundle-fetched bundle)
                                 (df/load-team-fonts (:team-id project)))))
             (rx/catch (fn [err]
                         (if (and (= (:type err) :restriction)
                                  (= (:code err) :feature-disabled))
                           (let [team-id (:current-team-id state)]
                             (rx/of (modal/show
                                      {:type :alert
                                       :message (tr "errors.components-v2")
                                       :on-accept #(st/emit! (rt/nav :dashboard-projects {:team-id team-id}))})))
                           (rx/throw err)))))))))

;; --- Helpers

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
      (update :workspace-pages dissoc id)))

(defn preload-data-uris
  "Preloads the image data so it's ready when necessary"
  []
  (ptk/reify ::preload-data-uris
    ptk/WatchEvent
    (watch [_ state _]
      (let [extract-urls
            (fn [{:keys [metadata fill-image]}]
              (cond
                (some? metadata)
                [(cf/resolve-file-media metadata)]

                (some? fill-image)
                [(cf/resolve-file-media fill-image)]))

            uris (into #{}
                       (comp (mapcat extract-urls)
                             (filter some?))
                       (vals (wsh/lookup-page-objects state)))]
        (->> (rx/from uris)
             (rx/merge-map #(http/fetch-data-uri % false))
             (rx/ignore))))))
