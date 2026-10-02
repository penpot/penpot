;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.merge
  "Three-way merge of file data.

  `base` is the data at the branch point, `ours` the current data of
  the core file and `theirs` the current data of the branch. Entities
  keep their ids across the three sides, so they are matched by id.

  Shape attrs are merged per sync group (`ctk/sync-attrs`), so derived
  geometry always travels with the attrs it derives from. An attr group
  changed on one side is taken; changed on both sides to different
  values is a conflict. Unresolved conflicts keep the `ours` value."
  (:require
   [app.common.data :as d]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [clojure.core.protocols :as cp]
   [clojure.set :as set]))

(def ^:private entity-collections
  [:components :colors :typographies :media])

(def ^:private handled-keys
  (into #{:id :pages :pages-index} entity-collections))

(def ^:private tree-attrs
  #{:parent-id :frame-id})

(def ^:private color-value-attrs
  #{:color :opacity :gradient :image})

(defn- shape-group
  [shape attr]
  (cond
    (= attr :shapes)        :shapes
    (contains? tree-attrs attr) :tree
    :else
    (let [group (get ctk/sync-attrs attr)]
      (cond
        (map? group) (get group (:type shape) attr)
        (some? group) group
        :else attr))))

(defn- color-group
  [_ attr]
  (if (contains? color-value-attrs attr) :value attr))

(defn- attr-group
  [_ attr]
  attr)

(defn- datafy-eq
  [a b]
  (= (cp/datafy a) (cp/datafy b)))

(defn change-key
  "Stable key of one changed entity; `merge-file-data` takes a set of
  them as `:excluded`."
  [kind page-id id]
  (str (name kind) "/" (or page-id "") "/" (if (keyword? id) (name id) id)))

(defn conflict-id
  [kind page-id id group]
  (str (change-key kind page-id id) "/" (if (keyword? group) (name group) group)))

;; --- Sequences

(defn merge-seq
  "Merge two edits of the `base` id vector. Starts from `ours`, drops
  the ids `theirs` removed and inserts the ids `theirs` added after
  their nearest preceding neighbour."
  [base ours theirs]
  (cond
    (= base theirs) ours
    (= base ours)   theirs
    :else
    (let [base-set    (set base)
          theirs-set  (set theirs)
          removed     (set/difference base-set theirs-set)
          ours'       (into [] (remove removed) ours)
          ours-set    (set ours')
          added       (into [] (remove #(or (contains? base-set %)
                                            (contains? ours-set %)))
                            theirs)]
      (reduce (fn [result id]
                (let [prev  (->> theirs
                                 (take-while #(not= % id))
                                 (filter (set result))
                                 (last))
                      index (if prev (inc (d/index-of result prev)) 0)]
                  (d/insert-at-index result index [id])))
              ours'
              added))))

;; --- Entities

(defn- group-keys
  [group-fn entity keys]
  (group-by #(group-fn entity %) keys))

(defn- merge-entity
  "Merge one entity present on the three sides. Returns
  `{:value v :conflicts [..]}`."
  [ctx base ours theirs]
  (let [{:keys [kind page-id group-fn resolutions eq]} ctx
        id      (:id ours)
        all     (-> #{} (into (keys base)) (into (keys ours)) (into (keys theirs)))
        groups  (group-keys group-fn ours all)]
    (reduce-kv
     (fn [{:keys [value] :as result} group ks]
       (let [pick (fn [entity] (select-keys entity ks))
             b    (pick base)
             o    (pick ours)
             t    (pick theirs)
             take-side (fn [value side] (-> (apply dissoc value ks) (merge side)))]
         (cond
           (eq b t) result
           (eq b o) (assoc result :value (take-side value t))

           (= group :shapes)
           (assoc result :value
                  (assoc value :shapes (merge-seq (:shapes base) (:shapes ours) (:shapes theirs))))

           (eq o t) result

           :else
           (let [cid (conflict-id kind page-id id group)]
             (cond-> (update result :conflicts conj
                             {:id cid :key (change-key kind page-id id) :kind kind
                              :page-id page-id :entity-id id
                              :name (:name ours) :group group :attrs (vec (sort ks))
                              :base b :ours o :theirs t})
               (= :theirs (get resolutions cid))
               (assoc :value (take-side value t)))))))
     {:value ours :conflicts []}
     groups)))

(defn- change-entry
  [ctx id base theirs]
  (let [{:keys [kind page-id group-fn eq owner-fn]} ctx
        entry (fn [op name & {:as attrs}]
                (merge {:key (change-key kind page-id id)
                        :kind kind :page-id page-id :id id :op op :name name}
                       (when owner-fn (owner-fn id))
                       attrs))]
    (cond
      (nil? base)   (entry :add (:name theirs))
      (nil? theirs) (entry :del (:name base))
      :else
      (let [all    (-> #{} (into (keys base)) (into (keys theirs)))
            groups (->> (group-keys group-fn theirs all)
                        (keep (fn [[group ks]]
                                (when-not (eq (select-keys base ks) (select-keys theirs ks))
                                  group)))
                        (sort-by str)
                        (vec))]
        (when (seq groups)
          (entry :mod (:name theirs) :groups groups))))))

(defn- merge-entities
  "Merge three `id -> entity` maps. Returns
  `{:value map :conflicts [..] :changes [..]}`."
  [ctx base ours theirs]
  (let [{:keys [kind page-id resolutions excluded eq]} ctx
        ids (-> #{} (into (keys base)) (into (keys ours)) (into (keys theirs)))]
    (reduce
     (fn [result id]
       (let [b (get base id)
             o (get ours id)
             t (get theirs id)
             result (if-let [change (change-entry ctx id b t)]
                      (update result :changes conj change)
                      result)
             t (if (contains? excluded (change-key kind page-id id)) b t)
             delete-conflict
             (fn [result]
               (let [cid (conflict-id kind page-id id :entity)]
                 (cond-> (update result :conflicts conj
                                 {:id cid :key (change-key kind page-id id) :kind kind
                                  :page-id page-id :entity-id id
                                  :name (:name (or o t b)) :group :entity
                                  :ours (some? o) :theirs (some? t)})
                   (= :theirs (get resolutions cid))
                   (update :value (fn [m] (if t (assoc m id t) (dissoc m id)))))))]
         (cond
           (eq t b) result

           (nil? b)
           (cond
             (nil? o)  (update result :value assoc id t)
             (eq o t)  result
             :else     (delete-conflict result))

           (nil? t)
           (cond
             (nil? o)  result
             (eq o b)  (update result :value dissoc id)
             :else     (delete-conflict result))

           (nil? o)
           (delete-conflict result)

           :else
           (let [{:keys [value conflicts]} (merge-entity ctx b o t)]
             (-> result
                 (update :value assoc id value)
                 (update :conflicts into conflicts))))))
     {:value (or ours {}) :conflicts [] :changes []}
     (sort-by str ids))))

;; --- Shape tree

(defn- repair-tree
  "Drop shapes whose parent no longer exists and make every `:shapes`
  vector agree with the children `:parent-id`."
  [objects]
  (let [objects
        (loop [objects objects]
          (let [orphans (into #{}
                              (comp (remove #(= uuid/zero (:id %)))
                                    (remove #(contains? objects (:parent-id %)))
                                    (map :id))
                              (vals objects))]
            (if (empty? orphans)
              objects
              (recur (apply dissoc objects orphans)))))

        children
        (reduce-kv (fn [index id shape]
                     (if (= id uuid/zero)
                       index
                       (update index (:parent-id shape) (fnil conj #{}) id)))
                   {}
                   objects)]

    (reduce-kv
     (fn [objects id shape]
       (let [own     (get children id #{})
             shapes  (into [] (filter own) (:shapes shape))
             missing (remove (set shapes) (sort-by str own))
             shapes  (into shapes missing)]
         (if (or (seq shapes) (contains? shape :shapes))
           (assoc-in objects [id :shapes] shapes)
           objects)))
     objects
     objects)))

;; --- File data

(defn- merge-top-level
  [{:keys [resolutions excluded]} base ours theirs]
  (let [ks (-> #{} (into (keys base)) (into (keys ours)) (into (keys theirs))
               (set/difference handled-keys))]
    (reduce
     (fn [result k]
       (let [eq (if (= k :tokens-lib) datafy-eq =)
             b  (get base k)
             o  (get ours k)
             key (change-key :data nil k)
             t  (if (contains? excluded key) b (get theirs k))
             cid (conflict-id :data nil k :value)
             change {:key key :kind :data :id k :op :mod :name (name k)}]
         (cond
           (eq b (get theirs k)) result
           (eq b t) (update result :changes conj change)
           (eq b o) (-> result
                        (update :value (fn [data] (if (some? t) (assoc data k t) (dissoc data k))))
                        (update :changes conj change))
           (eq o t) (update result :changes conj change)
           :else
           (cond-> (-> result
                       (update :changes conj change)
                       (update :conflicts conj
                               {:id cid :key key :kind :data :entity-id k :name (name k)
                                :group :value :ours (some? o) :theirs (some? t)}))
             (= :theirs (get resolutions cid))
             (update :value (fn [data] (if (some? t) (assoc data k t) (dissoc data k))))))))
     {:value ours :conflicts [] :changes []}
     (sort-by str ks))))

(defn- combine
  [result {:keys [conflicts changes]}]
  (-> result
      (update :conflicts into conflicts)
      (update :changes into changes)))

(defn- page-meta
  [page]
  (dissoc page :objects))

(defn- component-owner-fn
  "Return a fn that finds the component a shape belongs to: the nearest
  main instance up its parent chain, in `theirs` or else in `base`."
  [base theirs page-id]
  (let [objects    (fn [data] (get-in data [:pages-index page-id :objects]))
        theirs-obj (objects theirs)
        base-obj   (objects base)
        lookup     #(or (get theirs-obj %) (get base-obj %))]
    (fn [id]
      (loop [id id]
        (when-let [shape (when (not= id uuid/zero) (lookup id))]
          (if-let [component-id (when (:main-instance shape) (:component-id shape))]
            {:component-id component-id
             :component-name (:name (or (get-in theirs [:components component-id])
                                        (get-in base [:components component-id])))}
            (recur (:parent-id shape))))))))

(defn merge-file-data
  "Three-way merge of file data. Options:

  - `:resolutions` maps a conflict id to `:ours` or `:theirs`.
  - `:excluded` is a set of change keys (see `change-key`) whose
    `theirs` side is ignored, so the core keeps its own version.

  Returns `{:data merged :conflicts [..] :changes [..]}`, where
  `changes` lists what `theirs` changed since `base`."
  ([base ours theirs]
   (merge-file-data base ours theirs nil))
  ([base ours theirs {:keys [resolutions excluded] :as opts}]
   (let [ctx    {:resolutions resolutions :excluded (set excluded) :eq =}

         top    (merge-top-level ctx base ours theirs)
         result (assoc top :data (:value top))

         result
         (reduce
          (fn [result coll]
            (let [group-fn (if (#{:colors} coll) color-group attr-group)
                  merged   (merge-entities (cond-> (assoc ctx :kind coll :group-fn group-fn)
                                             (= coll :components)
                                             (assoc :owner-fn (fn [id]
                                                                {:component-id id
                                                                 :component-name (:name (or (get-in theirs [:components id])
                                                                                            (get-in base [:components id])))})))
                                           (get base coll) (get ours coll) (get theirs coll))]
              (-> result
                  (combine merged)
                  (assoc-in [:data coll] (:value merged)))))
          result
          entity-collections)

         pages
         (merge-entities (assoc ctx :kind :page :group-fn attr-group)
                         (update-vals (:pages-index base) page-meta)
                         (update-vals (:pages-index ours) page-meta)
                         (update-vals (:pages-index theirs) page-meta))

         result (combine result pages)

         pages-index
         (reduce-kv
          (fn [index page-id page]
            (let [objects (fn [data] (get-in data [:pages-index page-id :objects]))
                  merged  (merge-entities (assoc ctx
                                                 :kind :shape
                                                 :page-id page-id
                                                 :group-fn shape-group
                                                 :owner-fn (component-owner-fn base theirs page-id))
                                          (objects base) (objects ours) (objects theirs))]
              (assoc index page-id (-> merged
                                       (update :value repair-tree)
                                       (assoc :page page)))))
          {}
          (:value pages))

         result
         (reduce (fn [result merged] (combine result merged))
                 result
                 (vals pages-index))

         page-ids
         (->> (merge-seq (:pages base) (:pages ours) (:pages theirs))
              (filterv #(contains? pages-index %)))

         page-ids
         (into page-ids (remove (set page-ids)) (sort-by str (keys pages-index)))]

     (-> result
         (dissoc :value)
         (assoc-in [:data :pages] page-ids)
         (assoc-in [:data :pages-index]
                   (update-vals pages-index
                                (fn [{:keys [page value]}]
                                  (assoc page :objects value))))))))
