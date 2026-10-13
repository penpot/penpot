;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.changes-merge
  "Operations that rewrite a batch of changes that has already been built.

   These take a finished batch and return an equivalent, smaller one. They do
   not read the file data and do not build changes; for that, see the
   changes-builder.")

(defn- reg-objects-key
  "The container a `:reg-objects` applies to. The applier touches
  `:pages-index page-id` and `:components component-id`, and a missing id is a
  no-op, so the pair identifies the change."
  [change]
  [(:page-id change) (:component-id change)])

(defn- reg-objects-change
  "Build the merged `:reg-objects` for a container. `:page-id` and
  `:component-id` are only set when present, the change schema rejects an
  explicit nil."
  [[page-id component-id] shapes]
  (cond-> {:type :reg-objects
           :shapes (vec shapes)}
    page-id (assoc :page-id page-id)
    component-id (assoc :component-id component-id)))

(defn merge-reg-objects
  "Collapse the `:reg-objects` of a change batch into one per container.

   The component sync emits one per touched shape, so a large operation piles
   many of them onto the same few groups: one measured component swap carried
   291 of them for 36 containers. `:reg-objects` recomputes group geometry
   from the children, so a single run with the union of the shapes gives the
   same result as running each one in turn.

   The merged change goes to the end of the batch, not to the position of the
   first one it replaces. A group must be recomputed after the shapes that
   resized it, otherwise it keeps a stale `:selrect` and the mismatch does not
   show until something else touches the group.

   Works the same for `:undo-changes`, whose list already runs in reverse
   order: the merged change lands at the end there too, so the group is
   recomputed once the shapes are back to their previous values."
  [changes]
  (let [regs (filter #(= :reg-objects (:type %)) changes)
        ;; Containers keyed by [page-id component-id], remembering the order
        ;; they first showed up so the merged batch is deterministic.
        merged (reduce (fn [acc change]
                         (let [k (reg-objects-key change)]
                           (if (contains? (:groups acc) k)
                             (update-in acc [:groups k] into (:shapes change))
                             (-> acc
                                 (assoc-in [:groups k] (set (:shapes change)))
                                 (update :order conj k)))))
                       {:order [] :groups {}}
                       regs)]

    (into (vec (remove #(= :reg-objects (:type %)) changes))
          (map (fn [k]
                 (reg-objects-change k (get-in merged [:groups k])))
               (:order merged)))))