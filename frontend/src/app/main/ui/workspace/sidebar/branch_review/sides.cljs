;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-review.sides
  "The two sides of a change, main and the branch. The diff carries whole
  entities for conflicts and for added or deleted shapes; for a modified
  entity the open file holds one side and the changed attrs give the
  other, which is enough to put a page together as either side has it."
  (:require
   [app.common.types.shape :as cts]))

(def ^:private content-shape-types
  "Shape types whose geometry is their content; the others keep it in
  their position and size."
  #{:path :bool})

(defn- with-values
  "`entity` with each attr of `attrs` (`{attr {:source .. :branch ..}}`)
  set to the value `side-of` picks a side for; an attr it picks no side
  for stays as it is."
  [entity attrs side-of]
  (let [changed (into [] (filter (comp some? side-of)) (keys attrs))
        entity  (reduce (fn [entity attr]
                          (let [v (get-in attrs [attr (side-of attr)])]
                            (if (nil? v)
                              (dissoc entity attr)
                              (assoc entity attr v))))
                        entity
                        changed)]
    ;; the diff leaves out `:selrect` and `:points` (they follow from
    ;; the geometry), so a layer that moved or resized gets them again
    ;; from its own, or it would be placed with the open side's: a path
    ;; or a boolean from its content, any other from its position and
    ;; size
    (cond
      (and (contains? content-shape-types (:type entity))
           (some #{:content} changed))
      (cts/setup-path (dissoc entity :selrect :points))

      (and (not (contains? content-shape-types (:type entity)))
           (some #{:x :y :width :height} changed))
      (cts/setup-rect (dissoc entity :selrect :points))

      :else
      entity)))

(defn side-entity
  "One side (`:source` or `:branch`) of diff entry `item`. A conflict
  carries both; a clean change carries the attrs that differ, so the side
  the open file does not hold is the open entity with those attrs swapped
  in. An entry that does not exist on a side has no entity there: `origin`
  is the side that made the change, so what it added is missing on the
  other one and what it deleted is missing on its own. An added or
  deleted shape the open file lacks comes whole in the entry's `:shape`."
  [item side open-side origin open-entity]
  (cond
    (= :conflict (:status item))
    (get item side)

    (and (= :added (:status item)) (not= side origin))
    nil

    (and (= :deleted (:status item)) (= side origin))
    nil

    (= side open-side)
    open-entity

    (nil? open-entity)
    (:shape item)

    :else
    (with-values open-entity (:changed-attrs item) (constantly side))))

(defn- put-entity
  "`objects` with shape `id` as `entity`. Nil takes the shape out of its
  parent, so boards draw without it; a shape `objects` lacked is put
  into its parent, so boards draw it."
  [objects id entity]
  (if (some? entity)
    (let [parent-id (:parent-id entity)]
      (cond-> (assoc objects id entity)
        (and (contains? objects parent-id)
             (not (contains? objects id))
             (not (some #(= id %) (get-in objects [parent-id :shapes]))))
        (update-in [parent-id :shapes] (fnil conj []) id)))
    (let [parent-id (:parent-id (get objects id))]
      (cond-> (dissoc objects id)
        (contains? objects parent-id)
        (update-in [parent-id :shapes] (fn [ids] (filterv #(not= id %) ids)))))))

(defn side-objects
  "The page `objects` as they stand on `side`, with every shape change of
  `items` that lives on this page put on that side. `origin` is the side
  that made the changes: the branch, or main for the changes an update
  brings (see `side-entity`)."
  ([objects items side open-side]
   (side-objects objects items side open-side :branch))
  ([objects items side open-side origin]
   (reduce
    (fn [objects item]
      (if (not= :shape (:kind item))
        objects
        (let [id (:id item)]
          (put-entity objects id (side-entity item side open-side origin (get objects id))))))
    objects
    items)))

(defn resolved-objects
  "The page `objects` of the open branch with the choices made so far for
  `conflicts` (`resolutions`, by conflict id): a layer takes main's
  value for each property main was chosen for, main's whole version when
  main was chosen for all of it (none when main deleted it), and stays
  as the branch has it otherwise."
  [objects conflicts resolutions]
  (reduce
   (fn [objects {:keys [id] :as conflict}]
     (let [res  (get resolutions id)
           open (get objects id)]
       (cond
         (not= :shape (:kind conflict)) objects
         (= :source res)                (put-entity objects id (:source conflict))
         (and (map? res) (some? open))
         (put-entity objects id
                     (with-values open (:changed-attrs conflict)
                       #(when (= :source (get res %)) :source)))
         :else objects)))
   objects
   conflicts))
