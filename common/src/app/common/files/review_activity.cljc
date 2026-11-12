;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.files.review-activity
  "What the saves of a branch under design review did to its layers.

  The backend records one row per branch save while a review is open
  (`file_pull_request_activity`). `entries` builds that row from the
  change vector of the save and the file data before and after it: one
  entry per touched layer, with the kind of change and, for a modified
  layer, the value before and after of each property a person reads.

  The reading side folds those rows: `net-entries` gives what each layer
  ends up as since the review opened (a layer added and then deleted is
  no change at all), and `sessions` cuts the rows into the runs of saves
  one person made in one sitting, the groups the activity list shows."
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]))

(def tracked-attrs
  "The properties whose value before and after a modified entry keeps:
  the ones a person reads in the review, plus the applied tokens, which
  tell that a value now comes from a token."
  #{:name :fills :strokes :r1 :r2 :r3 :r4 :x :y :width :height :rotation
    :opacity :hidden :blocked :blend-mode :content :shadow :blur
    :layout-gap :layout-padding :layout-flex-dir :layout-item-h-sizing
    :layout-item-v-sizing :constraints-h :constraints-v :grow-type
    :applied-tokens :parent-id})

(def ^:private derived-attrs
  "The properties the editor recomputes from others. A save that touches
  only these (the text position data recomputed after an edit, the
  geometry kept in sync) is not a change a person made."
  #{:position-data :selrect :points :transform :transform-inverse :touched})

;; --- Building a row

(defn- add-touch
  "Fold one more touch of a layer into what the save did to it so far."
  [prev kind attrs]
  (case [(:kind prev) kind]
    [nil :added]          {:kind :added}
    [nil :deleted]        {:kind :deleted}
    [nil :modified]       {:kind :modified :attrs attrs}
    [:added :modified]    prev
    [:added :deleted]     {:kind :dropped}
    [:added :added]       prev
    [:modified :modified] (update prev :attrs into attrs)
    [:modified :deleted]  {:kind :deleted}
    [:modified :added]    prev
    [:deleted :added]     {:kind :modified :attrs #{}}
    [:deleted :modified]  {:kind :modified :attrs attrs}
    [:deleted :deleted]   prev
    [:dropped :added]     {:kind :added}
    prev))

(defn- set-attrs
  [operations]
  (into #{}
        (keep (fn [{:keys [type attr]}]
                (when (= :set type) attr)))
        operations))

(defn touched
  "What `changes` did to each layer of a page: `{[page-id id] {:kind
  :attrs}}`, `:kind` one of `:added`, `:modified`, `:deleted` and
  `:attrs` the properties a modification set. Changes to component
  definitions and to file-level assets are not layers of a page and are
  left out."
  [changes]
  (->> changes
       (reduce
        (fn [acc {:keys [type page-id id] :as change}]
          (if (nil? page-id)
            acc
            (case type
              :add-obj
              (update acc [page-id id] add-touch :added nil)

              :del-obj
              (update acc [page-id id] add-touch :deleted nil)

              :mod-obj
              (update acc [page-id id] add-touch :modified (set-attrs (:operations change)))

              :mov-objects
              (reduce (fn [acc shape-id]
                        (update acc [page-id shape-id] add-touch :modified #{:parent-id}))
                      acc
                      (:shapes change))

              acc)))
        {})
       (into {} (remove (fn [[_ {:keys [kind]}]] (= :dropped kind))))))

(defn- get-object
  [data page-id id]
  (-> data
      (get :pages-index)
      (get page-id)
      (get :objects)
      (get id)))

(defn- frame-name
  [data page-id frame-id]
  (when (and (some? frame-id) (not= uuid/zero frame-id))
    (:name (get-object data page-id frame-id))))

(defn collect-before
  "The state before the save of every layer the save touches: the part
  of each object `entries` needs, read from the file data the save
  starts from. Only the touched layers are read, so a page the save does
  not touch is never loaded."
  [data touched]
  (reduce-kv
   (fn [acc [page-id id :as key] _]
     (if-let [obj (get-object data page-id id)]
       (assoc acc key (-> (select-keys obj tracked-attrs)
                          (assoc :type (:type obj))
                          (assoc :frame-id (:frame-id obj))
                          (assoc :frame-name (frame-name data page-id (:frame-id obj)))))
       acc))
   {}
   touched))

(defn- summary
  [obj page-id id frame-name]
  {:id id
   :page-id page-id
   :name (:name obj)
   :type (:type obj)
   :frame-id (:frame-id obj)
   :frame-name frame-name})

(defn- attr-diff
  [before after attrs]
  (into {}
        (keep (fn [attr]
                (let [b (get before attr)
                      a (get after attr)]
                  (when (not= b a)
                    [attr [b a]]))))
        (filter tracked-attrs attrs)))

(defn- root-of-kind?
  "Whether the layer is the top of what the save added (or deleted): a
  layer whose parent was added in the same save comes with it, and the
  entry of its parent stands for both."
  [touched kind page-id parent-id]
  (not= kind (get-in touched [[page-id parent-id] :kind])))

(defn entries
  "The activity entries of one save, from the state `before` it
  (`collect-before`), the file data `after` it and what it `touched`.

  Every layer gets one entry, except the root layer, the children of a
  layer the same save added or deleted, and a layer whose only change is
  one the editor derives (`derived-attrs`)."
  [before after touched]
  (into []
        (keep
         (fn [[[page-id id :as key] {:keys [kind attrs]}]]
           (when (not= uuid/zero id)
             (let [prev (get before key)
                   obj  (get-object after page-id id)]
               (case kind
                 :added
                 (when (and (some? obj)
                            (root-of-kind? touched :added page-id (:parent-id obj)))
                   (-> (summary obj page-id id (frame-name after page-id (:frame-id obj)))
                       (assoc :kind :added)))

                 :deleted
                 (when (and (some? prev)
                            (root-of-kind? touched :deleted page-id (:parent-id prev)))
                   (-> (summary prev page-id id (:frame-name prev))
                       (assoc :kind :deleted)))

                 :modified
                 (when (and (some? obj) (some? prev))
                   (let [diff (attr-diff prev obj attrs)]
                     (when (or (seq diff)
                               (seq (remove derived-attrs attrs)))
                       (-> (summary obj page-id id (frame-name after page-id (:frame-id obj)))
                           (assoc :kind :modified)
                           (assoc :attrs diff)))))

                 nil)))))
        touched))

;; --- Reading the rows

(defn- fold-entry
  "Fold a later `entry` of a layer into what the layer is so far."
  [prev {:keys [kind] :as entry}]
  (case [(:kind prev) kind]
    [nil :added]          entry
    [nil :deleted]        entry
    [nil :modified]       entry
    [:added :modified]    (merge entry {:kind :added :attrs nil})
    [:added :deleted]     nil
    [:modified :modified] (assoc entry :attrs
                                 (merge-with (fn [[b _] [_ a]] [b a])
                                             (:attrs prev)
                                             (:attrs entry)))
    [:modified :deleted]  entry
    [:deleted :added]     (assoc entry :kind :modified :attrs {})
    [:deleted :modified]  entry
    entry))

(defn- drop-unchanged
  "A modification undone later (the value is back where it was) is no
  change: drop those attrs, and the entry when nothing else is left of
  it and it never had a visible attr."
  [{:keys [kind attrs] :as entry}]
  (if (= :modified kind)
    (let [attrs' (into {} (remove (fn [[_ [b a]]] (= b a))) attrs)]
      (when (or (seq attrs') (empty? attrs))
        (assoc entry :attrs attrs')))
    entry))

(defn net-entries
  "What each layer ends up as across `rows` (activity rows, each with
  `:profile-id`, `:created-at` and `:entries`), oldest first: one entry
  per layer, carrying who made its last change and when (`:profile-id`,
  `:changed-at`). A layer added and then deleted is gone; a modified
  property keeps its first value before and its last value after."
  [rows]
  (->> rows
       (sort-by :created-at)
       (reduce
        (fn [acc {:keys [profile-id created-at entries]}]
          (reduce
           (fn [acc {:keys [page-id id] :as entry}]
             (let [key   [page-id id]
                   entry (assoc entry
                                :profile-id profile-id
                                :changed-at created-at)
                   next  (fold-entry (get acc key) entry)]
               (if (nil? next)
                 (dissoc acc key)
                 (assoc acc key next))))
           acc
           entries))
        (d/ordered-map))
       (vals)
       (keep drop-unchanged)
       (sort-by :changed-at #(compare %2 %1))
       (vec)))

(def default-session-gap-ms
  "Two saves of the same person further apart than this, in
  milliseconds, belong to different sittings."
  (* 30 60 1000))

(defn sessions
  "Cut `rows` into sittings: runs of saves of one person with no gap
  longer than `gap-ms` milliseconds between two of them, newest first.
  Each sitting has the person (`:profile-id`), when it started and ended
  and its own `net-entries`."
  ([rows] (sessions rows default-session-gap-ms))
  ([rows gap-ms]
   (->> rows
        (sort-by :created-at)
        (reduce
         (fn [acc {:keys [profile-id created-at] :as row}]
           (let [last-session (peek acc)]
             (if (and (some? last-session)
                      (= profile-id (:profile-id last-session))
                      (<= (- (inst-ms created-at) (inst-ms (:end last-session))) gap-ms))
               (conj (pop acc) (-> last-session
                                   (assoc :end created-at)
                                   (update :rows conj row)))
               (conj acc {:profile-id profile-id
                          :start created-at
                          :end created-at
                          :rows [row]}))))
         [])
        (keep (fn [{:keys [rows] :as session}]
                (let [entries (net-entries rows)]
                  (when (seq entries)
                    (-> session
                        (dissoc :rows)
                        (assoc :entries entries))))))
        (reverse)
        (vec))))
