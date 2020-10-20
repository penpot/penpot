;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.comments
  (:require
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.constants :as c]
   [app.main.data.workspace.common :as dwc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [app.util.transit :as t]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]))


(s/def ::comment-thread any?)
(s/def ::comment any?)

(declare create-draft-thread)
(declare clear-draft-thread)
(declare retrieve-comment-threads)
(declare refresh-comment-thread)
(declare handle-interrupt)
(declare handle-comment-layer-click)

(defn initialize-comments
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::start-commenting
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local assoc :commenting true))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter #(= ::finalize %) stream)]
        (rx/merge
         (rx/of (retrieve-comment-threads file-id))
         (->> stream
              (rx/filter ms/mouse-click?)
              (rx/switch-map #(rx/take 1 ms/mouse-position))
              (rx/mapcat #(rx/take 1 ms/mouse-position))
              (rx/map handle-comment-layer-click)
              (rx/take-until stoper))
         (->> stream
              (rx/filter dwc/interrupt?)
              (rx/map handle-interrupt)
              (rx/take-until stoper)))))))

(defn- handle-interrupt
  []
  (ptk/reify ::handle-interrupt
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-comments state)]
        (cond
          (:draft local)
          (update state :workspace-comments dissoc :draft)

          (:open local)
          (update state :workspace-comments dissoc :open)

          :else
          state)))))

;; Event responsible of the what should be executed when user clicked
;; on the comments layer. An option can be create a new draft thread,
;; an other option is close previously open thread or cancel the
;; latest opened thread draft.
(defn- handle-comment-layer-click
  [position]
  (ptk/reify ::handle-comment-layer-click
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-comments state)]
        (if (:open local)
          (update state :workspace-comments dissoc :open)
          (update state :workspace-comments assoc
                  :draft {:position position :content ""}))))))

(defn create-thread
  [data]
  (letfn [(created [{:keys [id comment] :as thread} state]
            (-> state
                (update :comment-threads assoc id (dissoc thread :comment))
                (update :workspace-comments assoc :draft nil :open id)
                (update-in [:comments id] assoc (:id comment) comment)))]

    (ptk/reify ::create-thread
      ptk/WatchEvent
      (watch [_ state stream]
        (let [file-id (get-in state [:workspace-file :id])
              page-id (:current-page-id state)
              params  (assoc data
                             :page-id page-id
                             :file-id file-id)]
          (->> (rp/mutation :create-comment-thread params)
               (rx/map #(partial created %))))))))

(defn update-comment-thread-status
  [{:keys [id] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify ::update-comment-thread-status
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comment-threads id] assoc :count-unread-comments 0))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :update-comment-thread-status {:id id})
           (rx/ignore)))))


(defn update-comment-thread
  [{:keys [id is-resolved] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify ::update-comment-thread

    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comment-threads id] assoc :is-resolved is-resolved))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :update-comment-thread {:id id :is-resolved is-resolved})
           (rx/ignore)))))


(defn add-comment
  [thread content]
  (us/assert ::comment-thread thread)
  (us/assert ::us/string content)
  (letfn [(created [comment state]
            (update-in state [:comments (:id thread)] assoc (:id comment) comment))]
    (ptk/reify ::create-comment
      ptk/WatchEvent
      (watch [_ state stream]
        (rx/concat
         (->> (rp/mutation :add-comment {:thread-id (:id thread) :content content})
              (rx/map #(partial created %)))
         (rx/of (refresh-comment-thread thread)))))))

(defn update-comment
  [{:keys [id content thread-id] :as comment}]
  (us/assert ::comment comment)
  (ptk/reify :update-comment
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comments thread-id id] assoc :content content))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :update-comment {:id id :content content})
           (rx/ignore)))))

(defn delete-comment-thread
  [{:keys [id] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify :delete-comment-thread
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :comments dissoc id)
          (update :comment-threads dissoc id)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :delete-comment-thread {:id id})
           (rx/ignore)))))

(defn delete-comment
  [{:keys [id thread-id] :as comment}]
  (us/assert ::comment comment)
  (ptk/reify :delete-comment
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comments thread-id] dissoc id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :delete-comment {:id id})
           (rx/ignore)))))

(defn refresh-comment-thread
  [{:keys [id file-id] :as thread}]
  (us/assert ::comment-thread thread)
  (letfn [(fetched [thread state]
            (assoc-in state [:comment-threads id] thread))]
    (ptk/reify ::refresh-comment-thread
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :comment-thread {:file-id file-id :id id})
             (rx/map #(partial fetched %)))))))

(defn retrieve-comment-threads
  [file-id]
  (us/assert ::us/uuid file-id)
  (letfn [(fetched [data state]
            (assoc state :comment-threads (d/index-by :id data)))]
    (ptk/reify ::retrieve-comment-threads
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :comment-threads {:file-id file-id})
             (rx/map #(partial fetched %)))))))

(defn retrieve-comments
  [thread-id]
  (us/assert ::us/uuid thread-id)
  (letfn [(fetched [comments state]
            (update state :comments assoc thread-id (d/index-by :id comments)))]
    (ptk/reify ::retrieve-comments
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/query :comments {:thread-id thread-id})
             (rx/map #(partial fetched %)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace (local) events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open-thread
  [{:keys [id] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify ::open-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-comments assoc :open id :draft nil))))

(defn close-thread
  []
  (ptk/reify ::open-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-comments dissoc :open :draft))))


(defn- clear-draft-thread
  [state]
  (update state :workspace-comments dissoc :draft))

;; TODO: add specs

(defn update-draft-thread
  [data]
  (ptk/reify ::update-draft-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-comments assoc :draft data))))

(defn update-filters
  [{:keys [main resolved]}]
  (ptk/reify ::update-filters
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-comments
              (fn [local]
                (cond-> local
                  (some? main)
                  (assoc :filter main)

                  (some? resolved)
                  (assoc :filter-resolved resolved)))))))


(defn center-to-comment-thread
  [{:keys [id position] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify :center-to-comment-thread
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vbox vport zoom] :as local}]
                ;; (prn "position=" position)
                ;; (prn "vbox=" vbox)
                ;; (prn "vport=" vport)
                (let [pw (/ 50 zoom)
                      ph (/ 200 zoom)
                      nw (mth/round (- (/ (:width vbox) 2) pw))
                      nh (mth/round (- (/ (:height vbox) 2) ph))
                      nx (- (:x position) nw)
                      ny (- (:y position) nh)]
                  (update local :vbox assoc :x nx :y ny))))

      )))


