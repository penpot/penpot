;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.comments
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.events :as ev]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private schema:comment-thread
  (sm/define
    [:map {:title "CommentThread"}
     [:id ::sm/uuid]
     [:page-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:project-id ::sm/uuid]
     [:owner-id ::sm/uuid]
     [:page-name :string]
     [:file-name :string]
     [:seqn :int]
     [:content :string]
     [:participants ::sm/set-of-uuid]
     [:created-at ::sm/inst]
     [:modified-at ::sm/inst]
     [:position ::gpt/point]
     [:count-unread-comments {:optional true} :int]
     [:count-comments {:optional true} :int]]))

(def ^:private schema:comment
  (sm/define
    [:map {:title "Comment"}
     [:id ::sm/uuid]
     [:thread-id ::sm/uuid]
     [:owner-id ::sm/uuid]
     [:created-at ::sm/inst]
     [:modified-at ::sm/inst]
     [:content :string]]))

(def check-comment-thread!
  (sm/check-fn schema:comment-thread))

(def check-comment!
  (sm/check-fn schema:comment))

(declare create-draft-thread)
(declare retrieve-comment-threads)
(declare refresh-comment-thread)

(defn created-thread-on-workspace
  [{:keys [id comment page-id] :as thread}]
  (ptk/reify ::created-thread-on-workspace
    ptk/UpdateEvent
    (update [_ state]
      (let [position (select-keys thread [:position :frame-id])]
        (-> state
            (update :comment-threads assoc id (dissoc thread :comment))
            (update-in [:workspace-data :pages-index page-id :options :comment-threads-position] assoc id position)
            (update :comments-local assoc :open id)
            (update :comments-local dissoc :draft)
            (update :workspace-drawing dissoc :comment)
            (update-in [:comments id] assoc (:id comment) comment))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event ::ev/event
                             {::ev/name "create-comment-thread"
                              ::ev/origin "workspace"
                              :id id
                              :content-size (count (:content comment))})))))



(def ^:private
  schema:create-thread-on-workspace
  (sm/define
    [:map {:title "created-thread-on-workspace"}
     [:page-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:position ::gpt/point]
     [:content :string]]))

(defn create-thread-on-workspace
  [params]
  (dm/assert! (sm/check! schema:create-thread-on-workspace params))

  (ptk/reify ::create-thread-on-workspace
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            frame-id (ctst/get-frame-id-by-position objects (:position params))
            params (assoc params :frame-id frame-id)]
        (->> (rp/cmd! :create-comment-thread params)
             (rx/mapcat #(rp/cmd! :get-comment-thread {:file-id (:file-id %) :id (:id %)}))
             (rx/map created-thread-on-workspace)
             (rx/catch (fn [{:keys [type code] :as cause}]
                         (if (and (= type :restriction)
                                  (= code :max-quote-reached))
                           (rx/throw cause)
                           (rx/throw {:type :comment-error})))))))))

(defn created-thread-on-viewer
  [{:keys [id comment page-id] :as thread}]
  (ptk/reify ::created-thread-on-viewer
    ptk/UpdateEvent
    (update [_ state]
      (let [position (select-keys thread [:position :frame-id])]
        (-> state
            (update :comment-threads assoc id (dissoc thread :comment))
            (update-in [:viewer :pages page-id :options :comment-threads-position] assoc id position)
            (update :comments-local assoc :open id)
            (update :comments-local dissoc :draft)
            (update :workspace-drawing dissoc :comment)
            (update-in [:comments id] assoc (:id comment) comment))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event ::ev/event
                             {::ev/name "create-comment-thread"
                              ::ev/origin "viewer"
                              :id id
                              :content-size (count (:content comment))})))))

(def ^:private
  schema:create-thread-on-viewer
  (sm/define
    [:map {:title "created-thread-on-viewer"}
     [:page-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:frame-id ::sm/uuid]
     [:position ::gpt/point]
     [:content :string]]))

(defn create-thread-on-viewer
  [params]
  (dm/assert!
   (sm/check! schema:create-thread-on-viewer params))

  (ptk/reify ::create-thread-on-viewer
    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)
            frame-id (:frame-id params)
            params (assoc params :share-id share-id :frame-id frame-id)]
        (->> (rp/cmd! :create-comment-thread params)
             (rx/mapcat #(rp/cmd! :get-comment-thread {:file-id (:file-id %) :id (:id %) :share-id share-id}))
             (rx/map created-thread-on-viewer)
             (rx/catch (fn [{:keys [type code] :as cause}]
                         (if (and (= type :restriction)
                                  (= code :max-quote-reached))
                           (rx/throw cause)
                           (rx/throw {:type :comment-error})))))))))

(defn update-comment-thread-status
  [thread-id]
  (ptk/reify ::update-comment-thread-status
    ptk/WatchEvent
    (watch [_ state _]
      (let [done #(d/update-in-when % [:comment-threads thread-id] assoc :count-unread-comments 0)
            share-id (-> state :viewer-local :share-id)]
        (->> (rp/cmd! :update-comment-thread-status {:id thread-id :share-id share-id})
             (rx/map (constantly done))
             (rx/catch #(rx/throw {:type :comment-error})))))))

(defn update-comment-thread
  [{:keys [id is-resolved] :as thread}]

  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))

  (ptk/reify ::update-comment-thread
    IDeref
    (-deref [_] {:is-resolved is-resolved})

    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comment-threads id] assoc :is-resolved is-resolved))

    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)]
        (->> (rp/cmd! :update-comment-thread {:id id :is-resolved is-resolved :share-id share-id})
             (rx/catch (fn [{:keys [type code] :as cause}]
                         (if (and (= type :restriction)
                                  (= code :max-quote-reached))
                           (rx/throw cause)
                           (rx/throw {:type :comment-error}))))
             (rx/ignore))))))

(defn add-comment
  [thread content]

  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))

  (dm/assert!
   "expected valid content"
   (string? content))

  (ptk/reify ::create-comment
    ev/Event
    (-data [_]
      {:thread-id (:id thread)
       :file-id (:file-id thread)
       :content-size (count content)})

    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)
            created  (fn [comment state]
                       (update-in state [:comments (:id thread)] assoc (:id comment) comment))]
        (rx/concat
         (->> (rp/cmd! :create-comment {:thread-id (:id thread) :content content :share-id share-id})
              (rx/map (fn [comment] (partial created comment)))
              (rx/catch (fn [{:keys [type code] :as cause}]
                          (if (and (= type :restriction)
                                   (= code :max-quote-reached))
                            (rx/throw cause)
                            (rx/throw {:type :comment-error})))))
         (rx/of (refresh-comment-thread thread)))))))

(defn update-comment
  [{:keys [id content thread-id] :as comment}]
  (dm/assert!
   "expected valid comment"
   (check-comment! comment))

  (ptk/reify ::update-comment
    ev/Event
    (-data [_]
      {:thread-id thread-id
       :id id
       :content-size (count content)})

    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:comments thread-id id] assoc :content content))

    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)]
        (->> (rp/cmd! :update-comment {:id id :content content :share-id share-id})
             (rx/catch #(rx/throw {:type :comment-error}))
             (rx/ignore))))))

(defn delete-comment-thread-on-workspace
  [{:keys [id] :as thread}]
  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))
  (ptk/reify ::delete-comment-thread-on-workspace
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (-> state
            (update-in [:workspace-data :pages-index page-id :options :comment-threads-position] dissoc id)
            (update :comments dissoc id)
            (update :comment-threads dissoc id))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (->> (rp/cmd! :delete-comment-thread {:id id})
            (rx/catch #(rx/throw {:type :comment-error}))
            (rx/ignore))
       (rx/of (ptk/data-event ::ev/event
                              {::ev/name "delete-comment-thread"
                               ::ev/origin "workspace"
                               :id id}))))))

(defn delete-comment-thread-on-viewer
  [{:keys [id] :as thread}]
  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))
  (ptk/reify ::delete-comment-thread-on-viewer
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (-> state
            (update-in [:viewer :pages page-id :options :comment-threads-position] dissoc id)
            (update :comments dissoc id)
            (update :comment-threads dissoc id))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)]
        (rx/concat
         (->> (rp/cmd! :delete-comment-thread {:id id :share-id share-id})
              (rx/catch #(rx/throw {:type :comment-error}))
              (rx/ignore))
         (rx/of (ptk/data-event ::ev/event
                                {::ev/name "delete-comment-thread"
                                 ::ev/origin "viewer"
                                 :id id})))))))
(defn delete-comment
  [{:keys [id thread-id] :as comment}]
  (dm/assert!
   "expected valid comment"
   (check-comment! comment))
  (ptk/reify ::delete-comment
    ev/Event
    (-data [_]
      {:thread-id thread-id})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when [:comments thread-id] dissoc id)
          (d/update-in-when [:comment-threads thread-id :count-comments] dec)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [share-id (-> state :viewer-local :share-id)]
        (->> (rp/cmd! :delete-comment {:id id :share-id share-id})
             (rx/catch #(rx/throw {:type :comment-error}))
             (rx/ignore))))))

(defn refresh-comment-thread
  [{:keys [id file-id] :as thread}]
  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))
  (letfn [(fetched [thread state]
            (assoc-in state [:comment-threads id] thread))]
    (ptk/reify ::refresh-comment-thread
      ptk/WatchEvent
      (watch [_ state _]
        (let [share-id (-> state :viewer-local :share-id)]
          (->> (rp/cmd! :get-comment-thread {:file-id file-id :id id :share-id share-id})
               (rx/map #(partial fetched %))
               (rx/catch #(rx/throw {:type :comment-error}))))))))

(defn retrieve-comment-threads
  [file-id]
  (dm/assert! (uuid? file-id))
  (letfn [(set-comment-threds [state comment-thread]
            (let [path [:workspace-data :pages-index (:page-id comment-thread) :options :comment-threads-position (:id comment-thread)]
                  thread-position (get-in state path)]
              (cond-> state
                (nil? thread-position)
                (->
                 (assoc-in (conj path :position) (:position comment-thread))
                 (assoc-in (conj path :frame-id) (:frame-id comment-thread))))))
          (fetched [[users comments] state]
            (let [pages (-> (get-in state [:workspace-data :pages])
                            set)
                  comments (filter #(contains? pages (:page-id %)) comments)
                  state (-> state
                            (assoc :comment-threads (d/index-by :id comments))
                            (update :current-file-comments-users merge (d/index-by :id users)))]
              (reduce set-comment-threds state comments)))]

    (ptk/reify ::retrieve-comment-threads
      ptk/WatchEvent
      (watch [_ state _]
        (let [share-id (-> state :viewer-local :share-id)]
          (->> (rx/zip (rp/cmd! :get-team-users {:file-id file-id})
                       (rp/cmd! :get-comment-threads {:file-id file-id :share-id share-id}))
               (rx/take 1)
               (rx/map #(partial fetched %))
               (rx/catch #(rx/throw {:type :comment-error}))))))))

(defn retrieve-comments
  [thread-id]
  (dm/assert! (uuid? thread-id))
  (letfn [(fetched [comments state]
            (update state :comments assoc thread-id (d/index-by :id comments)))]
    (ptk/reify ::retrieve-comments
      ptk/WatchEvent
      (watch [_ state _]
        (let [share-id (-> state :viewer-local :share-id)]
          (->> (rp/cmd! :get-comments {:thread-id thread-id :share-id share-id})
               (rx/map #(partial fetched %))
               (rx/catch #(rx/throw {:type :comment-error}))))))))

(defn retrieve-unread-comment-threads
  "A event used mainly in dashboard for retrieve all unread threads of a team."
  [team-id]
  (dm/assert! (uuid? team-id))
  (ptk/reify ::retrieve-unread-comment-threads
    ptk/WatchEvent
    (watch [_ _ _]
      (let [fetched-comments #(assoc %2 :comment-threads (d/index-by :id %1))
            fetched-users #(assoc %2 :current-team-comments-users %1)]
        (->> (rp/cmd! :get-unread-comment-threads {:team-id team-id})
             (rx/merge-map
              (fn [comments]
                (rx/concat
                 (rx/of (partial fetched-comments comments))

                 (->> (rx/from (into #{} (map :file-id) comments))
                      (rx/merge-map #(rp/cmd! :get-profiles-for-file-comments {:file-id %}))
                      (rx/reduce #(merge %1 (d/index-by :id %2)) {})
                      (rx/map #(partial fetched-users %))))))
             (rx/catch #(rx/throw {:type :comment-error})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Local State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn open-thread
  [{:keys [id] :as thread}]
  (dm/assert!
   "expected valid comment thread"
   (check-comment-thread! thread))
  (ptk/reify ::open-comment-thread
    ev/Event
    (-data [_]
      {:thread-id id})

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :comments-local assoc :open id)
          (update :workspace-drawing dissoc :comment)))))

(defn close-thread
  []
  (ptk/reify ::close-comment-thread
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :comments-local dissoc :open :draft)
          (update :workspace-drawing dissoc :comment)))))

(defn update-filters
  [{:keys [mode show list] :as params}]
  (ptk/reify ::update-filters
    ptk/UpdateEvent
    (update [_ state]
      (update state :comments-local
              (fn [local]
                (cond-> local
                  (some? mode)
                  (assoc :mode mode)

                  (some? show)
                  (assoc :show show)

                  (some? list)
                  (assoc :list list)))))))

(defn update-options
  [params]
  (ptk/reify ::update-options
    ptk/UpdateEvent
    (update [_ state]
      (update state :comments-local merge params))))

(def ^:private
  schema:create-draft
  (sm/define
    [:map {:title "create-draft"}
     [:page-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:position ::gpt/point]]))

(defn create-draft
  [params]
  (dm/assert!
   (sm/check! schema:create-draft params))
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
  (let [{:keys [show mode]} cstate]
    (cond->> threads
      (= :pending show)
      (filter (comp not :is-resolved))

      (= :yours mode)
      (filter #(contains? (:participants %) (:id profile))))))

(defn update-comment-thread-frame
  ([thread]
   (update-comment-thread-frame thread uuid/zero))

  ([thread frame-id]
   (dm/assert!
    "expected valid comment thread"
    (check-comment-thread! thread))

   (ptk/reify ::update-comment-thread-frame
     ptk/UpdateEvent
     (update [_ state]
       (let [thread-id (:id thread)]
         (assoc-in state [:comment-threads thread-id :frame-id] frame-id)))

     ptk/WatchEvent
     (watch [_ _ _]
       (let [thread-id (:id thread)]
         (->> (rp/cmd! :update-comment-thread-frame {:id thread-id  :frame-id frame-id})
              (rx/catch #(rx/throw {:type :comment-error :code :update-comment-thread-frame}))
              (rx/ignore)))))))

(defn detach-comment-thread
  "Detach comment threads that are inside a frame when that frame is deleted"
  [ids]
  (dm/assert!
   "expected a valid coll of uuid's"
   (sm/check-coll-of-uuid! ids))

  (ptk/reify ::detach-comment-thread
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            is-frame? (fn [id] (= :frame (get-in objects [id :type])))
            frame-ids? (into #{} (filter is-frame?) ids)]

        (->> state
             :comment-threads
             (vals)
             (filter (fn [comment] (some #(= % (:frame-id comment)) frame-ids?)))
             (map update-comment-thread-frame)
             (rx/from))))))
