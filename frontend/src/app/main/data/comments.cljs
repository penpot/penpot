;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.comments
  (:require
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.constants :as c]
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

(s/def ::content ::us/string)
(s/def ::count-comments ::us/integer)
(s/def ::count-unread-comments ::us/integer)
(s/def ::created-at ::us/inst)
(s/def ::file-id ::us/uuid)
(s/def ::file-name ::us/string)
(s/def ::modified-at ::us/inst)
(s/def ::owner-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::page-name ::us/string)
(s/def ::participants (s/every ::us/uuid :kind set?))
(s/def ::position ::us/point)
(s/def ::project-id ::us/uuid)
(s/def ::seqn ::us/integer)
(s/def ::thread-id ::us/uuid)

(s/def ::comment-thread
  (s/keys :req-un [::us/id
                   ::page-id
                   ::file-id
                   ::project-id
                   ::page-name
                   ::file-name
                   ::seqn
                   ::content
                   ::participants
                   ::created-at
                   ::modified-at
                   ::owner-id
                   ::position]
          :opt-un [::count-unread-comments
                   ::count-comments]))

(s/def ::comment
  (s/keys :req-un [::us/id
                   ::thread-id
                   ::owner-id
                   ::created-at
                   ::modified-at
                   ::content]))

(declare create-draft-thread)
(declare retrieve-comment-threads)
(declare refresh-comment-thread)

(s/def ::create-thread-params
  (s/keys :req-un [::page-id ::file-id ::position ::content]))

(defn create-thread
  [params]
  (us/assert ::create-thread-params params)
  (letfn [(created [{:keys [id comment] :as thread} state]
            (-> state
                (update :comment-threads assoc id (dissoc thread :comment))
                (update :comments-local assoc :open id)
                (update :comments-local dissoc :draft)
                (update :workspace-drawing dissoc :comment)
                (update-in [:comments id] assoc (:id comment) comment)))]

    (ptk/reify ::create-thread
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rp/mutation :create-comment-thread params)
             (rx/mapcat #(rp/query :comment-thread {:file-id (:file-id %) :id (:id %)}))
             (rx/map #(partial created %)))))))

(defn update-comment-thread-status
  [{:keys [id] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify ::update-comment-thread-status
    ptk/WatchEvent
    (watch [_ state stream]
      (let [done #(d/update-in-when % [:comment-threads id] assoc :count-unread-comments 0)]
        (->> (rp/mutation :update-comment-thread-status {:id id})
             (rx/map (constantly done)))))))


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

(defn retrieve-unread-comment-threads
  "A event used mainly in dashboard for retrieve all unread threads of a team."
  [team-id]
  (us/assert ::us/uuid team-id)
  (ptk/reify ::retrieve-unread-comment-threads
    ptk/WatchEvent
    (watch [_ state stream]
      (let [fetched #(assoc %2 :comment-threads (d/index-by :id %1))]
        (->> (rp/query :unread-comment-threads {:team-id team-id})
             (rx/map #(partial fetched %)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Local State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open-thread
  [{:keys [id] :as thread}]
  (us/assert ::comment-thread thread)
  (ptk/reify ::open-thread
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :comments-local assoc :open id)
          (update :workspace-drawing dissoc :comment)))))

(defn close-thread
  []
  (ptk/reify ::close-thread
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :comments-local dissoc :open :draft)
          (update :workspace-drawing dissoc :comment)))))

(defn update-filters
  [{:keys [mode show] :as params}]
  (ptk/reify ::update-filters
    ptk/UpdateEvent
    (update [_ state]
      (update state :comments-local
              (fn [local]
                (cond-> local
                  (some? mode)
                  (assoc :mode mode)

                  (some? show)
                  (assoc :show show)))))))

(s/def ::create-draft-params
  (s/keys :req-un [::page-id ::file-id ::position]))

(defn create-draft
  [params]
  (us/assert ::create-draft-params params)
  (ptk/reify ::create-draft
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-drawing assoc :comment params)
          (update :comments-local assoc :draft params)))))

(defn update-draft-thread
  [data]
  (ptk/reify ::update-draft-thread
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:workspace-drawing :comment] merge data)
          (d/update-in-when [:comments-local :draft] merge data)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-threads-by-page
  [threads]
  (letfn [(group-by-page [result thread]
            (let [current (first result)]
              (if (= (:page-id current) (:page-id thread))
                (cons (update current :items conj thread)
                      (rest result))
                (cons {:page-id (:page-id thread)
                       :page-name (:page-name thread)
                       :items [thread]}
                      result))))]
    (reverse
     (reduce group-by-page nil threads))))


(defn group-threads-by-file-and-page
  [threads]
  (letfn [(group-by-file-and-page [result thread]
            (let [current (first result)]
              (if (and (= (:page-id current) (:page-id thread))
                       (= (:file-id current) (:file-id thread)))
                (cons (update current :items conj thread)
                      (rest result))
                (cons {:page-id (:page-id thread)
                       :page-name (:page-name thread)
                       :file-id (:file-id thread)
                       :file-name (:file-name thread)
                       :items [thread]}
                      result))))]
    (reverse
     (reduce group-by-file-and-page nil threads))))

(defn apply-filters
  [cstate profile threads]
  (let [{:keys [show mode open]} cstate]
    (cond->> threads
      (= :pending show)
      (filter (fn [item]
                (or (not (:is-resolved item))
                    (= (:id item) open))))

      (= :yours mode)
      (filter #(contains? (:participants %) (:id profile))))))
