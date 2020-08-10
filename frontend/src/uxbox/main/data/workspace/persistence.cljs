;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.persistence
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.main.data.dashboard :as dd]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.data.media :as di]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.object :as obj]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]))

(declare persist-changes)
(declare update-selection-index)
(declare shapes-changes-persisted)

;; --- Persistence

(defn initialize-page-persistence
  [page-id]
  (letfn [(enable-reload-stoper []
            (obj/set! js/window "onbeforeunload" (constantly false)))
          (disable-reload-stoper []
            (obj/set! js/window "onbeforeunload" nil))]
    (ptk/reify ::initialize-persistence
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :current-page-id page-id))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper   (rx/filter #(= ::finalize %) stream)
              notifier (->> stream
                            (rx/filter (ptk/type? ::dwc/commit-changes))
                            (rx/debounce 2000)
                            (rx/merge stoper))]
          (rx/merge
           (->> stream
                (rx/filter (ptk/type? ::dwc/commit-changes))
                (rx/map deref)
                (rx/tap enable-reload-stoper)
                (rx/buffer-until notifier)
                (rx/map vec)
                (rx/filter (complement empty?))
                (rx/map #(persist-changes page-id %))
                (rx/take-until (rx/delay 100 stoper)))
           (->> stream
                (rx/filter (ptk/type? ::changes-persisted))
                (rx/tap disable-reload-stoper)
                (rx/ignore)
                (rx/take-until stoper))))))))

(defn persist-changes
  [page-id changes]
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [sid     (:session-id state)
            page    (get-in state [:workspace-pages page-id])
            changes (into [] (mapcat identity) changes)
            params  {:id (:id page)
                     :revn (:revn page)
                     :session-id sid
                     :changes changes}]
        (->> (rp/mutation :update-page params)
             (rx/map (fn [lagged]
                       (if (= #{sid} (into #{} (map :session-id) lagged))
                         (map #(assoc % :changes []) lagged)
                         lagged)))
             (rx/mapcat seq)
             (rx/map shapes-changes-persisted))))))

(s/def ::shapes-changes-persisted
  (s/keys :req-un [::page-id ::revn ::cp/changes]))

(defn shapes-changes-persisted
  [{:keys [page-id revn changes] :as params}]
  (us/verify ::shapes-changes-persisted params)
  (ptk/reify ::changes-persisted
    ptk/UpdateEvent
    (update [_ state]
      (let [sid   (:session-id state)
            page  (get-in state [:workspace-pages page-id])
            state (update-in state [:workspace-pages page-id :revn] #(max % revn))]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (update-in [:workspace-pages page-id :data] cp/process-changes changes))))))


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
(s/def ::metadata (s/nilable ::cp/metadata))
(s/def ::data ::cp/data)

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

(declare bundle-fetched)

(defn- fetch-bundle
  [project-id file-id]
  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/zip (rp/query :file {:id file-id})
                   (rp/query :file-users {:id file-id})
                   (rp/query :project-by-id {:project-id project-id})
                   (rp/query :pages {:file-id file-id}))
           (rx/first)
           (rx/map (fn [[file users project pages]]
                     (bundle-fetched file users project pages)))
           (rx/catch (fn [{:keys [type code] :as error}]
                       (cond
                         (= :not-found type)
                         (rx/of (rt/nav' :not-found))

                         (and (= :authentication type)
                              (= :unauthorized code))
                         (rx/of (rt/nav' :not-authorized))

                         :else
                         (throw error))))))))

(defn- bundle-fetched
  [file users project pages]
  (ptk/reify ::bundle-fetched
    IDeref
    (-deref [_]
      {:file file
       :users users
       :project project
       :pages pages})

    ptk/UpdateEvent
    (update [_ state]
      (let [assoc-page #(assoc-in %1 [:workspace-pages (:id %2)] %2)]
        (as-> state $$
          (assoc $$
                 :workspace-file file
                 :workspace-users (d/index-by :id users)
                 :workspace-pages {}
                 :workspace-project project)
          (reduce assoc-page $$ pages))))))

;; --- Set File shared

(defn set-file-shared
  [id is-shared]
  {:pre [(uuid? id) (boolean? is-shared)]}
  (ptk/reify ::set-file-shared
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :is-shared] is-shared))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :is-shared is-shared}]
        (->> (rp/mutation :set-file-shared params)
             (rx/ignore))))))

;; --- Fetch Pages

(declare page-fetched)

(defn fetch-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::fetch-pages
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :page {:id page-id})
           (rx/map page-fetched)))))

(defn page-fetched
  [{:keys [id] :as page}]
  (us/verify ::page page)
  (ptk/reify ::page-fetched
    IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-pages id] page))))


;; --- Page Crud

(declare page-created)

(def create-empty-page
  (ptk/reify ::create-empty-page
    ptk/WatchEvent
    (watch [this state stream]
      (let [file-id (get-in state [:workspace-file :id])
            name (name (gensym "Page "))
            ordering (count (get-in state [:workspace-file :pages]))
            params {:name name
                    :file-id file-id
                    :ordering ordering
                    :data cp/default-page-data}]
        (->> (rp/mutation :create-page params)
             (rx/map page-created))))))

(defn page-created
  [{:keys [id file-id] :as page}]
  (us/verify ::page page)
  (ptk/reify ::page-created
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-file :pages] (fnil conj []) id)
          (assoc-in [:workspace-pages id] page)))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-page :id])
            state (assoc-in state [:workspace-pages id :name] name)]
        (cond-> state
          (= pid id) (assoc-in [:workspace-page :name] name))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-page params)
             (rx/map #(ptk/data-event ::page-renamed params)))))))

(declare purge-page)
(declare go-to-file)

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (purge-page state id))

    ptk/WatchEvent
    (watch [_ state s]
      (let [page (:workspace-page state)]
        (rx/merge
         (->> (rp/mutation :delete-page  {:id id})
              (rx/flat-map (fn [_]
                             (if (= id (:id page))
                               (rx/of go-to-file)
                               (rx/empty))))))))))

;; --- Fetch Workspace Media library

(declare media-library-fetched)

(defn fetch-media-library
  [file-id]
  (ptk/reify ::fetch-media-library
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :media-objects {:file-id file-id :is-local false})
           (rx/map media-library-fetched)))))

(defn media-library-fetched
  [media-objects]
  (ptk/reify ::media-library-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [media-objects (d/index-by :id media-objects)]
        (assoc state :workspace-media-library media-objects)))))

;; --- Fetch Workspace Colors library

(declare colors-library-fetched)

(defn fetch-colors-library
  [file-id]
  (ptk/reify ::fetch-colors-library
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :colors {:file-id file-id})
           (rx/map colors-library-fetched)))))

(defn colors-library-fetched
  [colors]
  (ptk/reify ::colors-library-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [colors (d/index-by :id colors)]
        (assoc state :workspace-colors-library colors)))))


;; --- Upload local media objects

(declare upload-media-objects-result)

(defn add-media-object-from-url
  ([file-id is-local url] (add-media-object-from-url file-id is-local url identity))
  ([file-id is-local url on-added]
   (us/verify ::us/url url)
   (us/verify fn? on-added)
   (us/verify ::us/boolean is-local)
   (ptk/reify ::add-media-object-from-url
     ptk/WatchEvent
     (watch [_ state stream]
       (let [on-success #(do (di/notify-finished-loading)
                             (on-added %))

             on-error #(do (di/notify-finished-loading)
                           (di/process-error %))
 
             prepare
             (fn [url]
               {:file-id file-id
                :is-local is-local
                :url url})]

         (di/notify-start-loading)

         (->> (rx/of url)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :add-media-object-from-url %))
              (rx/do on-success)
              (rx/map (partial upload-media-objects-result file-id is-local))
              (rx/catch on-error)))))))

(defn upload-media-objects
  ([file-id is-local js-files] (upload-media-objects file-id is-local js-files identity))
  ([file-id is-local js-files on-uploaded]
   (us/verify ::us/uuid file-id)
   (us/verify ::us/boolean is-local)
   (us/verify ::di/js-files js-files)
   (us/verify fn? on-uploaded)
   (ptk/reify ::upload-media-objects
     ptk/WatchEvent
     (watch [_ state stream]
       (let [on-success #(do (di/notify-finished-loading)
                             (on-uploaded %))

             on-error #(do (di/notify-finished-loading)
                           (di/process-error %))

             prepare
             (fn [js-file]
               {:name (.-name js-file)
                :file-id file-id
                :content js-file
                :is-local is-local})]

         (di/notify-start-loading)

         (->> (rx/from js-files)
              (rx/map di/validate-file)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :upload-media-object %))
              (rx/do on-success)
              (rx/map (partial upload-media-objects-result file-id is-local))
              (rx/catch on-error)))))))

(defn upload-media-objects-result
  [file-id is-local media-object]
  (us/verify ::us/uuid file-id)
  (us/verify ::us/boolean is-local)
  (us/verify ::di/media-object media-object)
  (ptk/reify ::upload-media-objects-result
    ptk/UpdateEvent
    (update [_ state]
      (if is-local
        state
        (assoc-in state
                  [:workspace-media-library (:id media-object)]
                  media-object)))))


;; --- Delete media object

(defn delete-media-object
  [id]
  (ptk/reify ::delete-media-object
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-media-library dissoc id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id}]
        (rp/mutation :delete-media-object params)))))


;; --- Helpers

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
      (update :workspace-pages dissoc id)))

