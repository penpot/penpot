;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
;;
;; High level helpers to turn a shape subtree into a component and
;; replace equivalent subtrees by instances of that component.

(ns app.main.data.workspace.componentize
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; NOTE: We keep this separate from `workspace.libraries` to avoid
;; introducing more complexity in that already big namespace.

(def ^:private instance-structural-keys
  "Keys we do NOT want to copy from the original shape when creating a
  new component instance. These are identity / structural / component
  metadata keys that must be managed by the component system itself."
  #{:id
    :parent-id
    :frame-id
    :shapes
    ;; Component metadata
    :component-id
    :component-file
    :component-root
    :main-instance
    :remote-synced
    :shape-ref
    :touched})

(def ^:private instance-geometry-keys
  "Geometry-related keys that we *do* want to override per instance when
  copying props from an existing subtree to a component instance."
  #{:x
    :y
    :width
    :height
    :rotation
    :flip-x
    :flip-y
    :selrect
    :points
    :proportion
    :proportion-lock
    :transform
    :transform-inverse})

(defn- instantiate-similar-subtrees
  "Internal helper. Given an atom `id-ref` that will contain the
  `component-id`, replace each subtree rooted at the ids in
  `similar-ids` by an instance of that component.

  The operation is performed in a single undo transaction:
  - Instantiate the component once per similar id, roughly at the same
    top-left position as the original root.
  - Delete the original subtrees.
  - Select the main instance plus all the new instances."
  [id-ref root-id similar-ids]
  (ptk/reify ::instantiate-similar-subtrees
    ptk/WatchEvent
    (watch [it state _]
      (let [component-id @id-ref
            similar-ids   (vec (or similar-ids []))]
        (if (or (uuid/zero? component-id)
                (empty? similar-ids))
          (rx/empty)
          (let [file-id   (:current-file-id state)
                page      (dsh/lookup-page state)
                page-id   (:id page)
                objects   (:objects page)
                libraries (dsh/lookup-libraries state)
                fdata     (dsh/lookup-file-data state file-id)

                ;; Reference subtree: shapes used to build the component.
                ;; We'll compute per-shape deltas against this subtree so
                ;; that we only override attributes that actually differ.
                ref-subtree-ids (cfh/get-children-ids objects root-id)
                ref-all-ids     (into [root-id] ref-subtree-ids)

                undo-id   (js/Symbol)

                ;; 1) Instantiate component at each similar root position,
                ;;    preserving per-instance overrides (geometry, style, etc.)
                [changes new-root-ids]
                (reduce
                 (fn [[changes acc] sid]
                   (if-let [shape (get objects sid)]
                     (let [position (gpt/point (:x shape) (:y shape))
                           ;; Remember original parent and index so we can keep
                           ;; the same ordering among the parent's children.
                           orig-root        (get objects sid)
                           orig-parent-id   (:parent-id orig-root)
                           orig-index       (when orig-parent-id
                                              (cfh/get-position-on-parent objects sid))
                           ;; Instantiate a new component instance at the same position
                           [new-shape changes']
                           (cll/generate-instantiate-component
                            (or changes
                                (-> (pcb/empty-changes it page-id)
                                    (pcb/with-objects objects)))
                            objects
                            file-id
                            component-id
                            position
                            page
                            libraries)
                           ;; Build a structural mapping between the original subtree
                           ;; (rooted at `sid`) and the new instance subtree.
                           ;; NOTE 1: instantiating a component can introduce an extra
                           ;; wrapper frame, so we try to align the original root
                           ;; with the "equivalent" root inside the instance.
                           ;; NOTE 2: by default the instance may be created *inside*
                           ;; the original shape (because of layout / hit-testing).
                           ;; We explicitly move the new instance to the same parent
                           ;; and index as the original root, so that later deletes of
                           ;; the original subtree don't remove the new instances and
                           ;; the ordering among siblings is preserved.
                           changes'         (cond-> changes'
                                              (some? orig-parent-id)
                                              (pcb/change-parent orig-parent-id [new-shape] orig-index
                                                                 {:allow-altering-copies true
                                                                  :ignore-touched true}))
                           objects'         (pcb/get-objects changes')
                           orig-root        (get objects sid)
                           new-root         new-shape
                           orig-type        (:type orig-root)
                           new-type         (:type new-root)
                           ;; Full original subtree (root + descendants)
                           orig-subtree-ids (cfh/get-children-ids objects sid)
                           orig-all-ids     (into [sid] orig-subtree-ids)
                           ;; Try to find an inner instance root matching the original type
                           ;; when the outer instance root type differs (e.g. rect -> frame+rect).
                           direct-new-children (cfh/get-children-ids objects' (:id new-root))
                           candidate-instance-root
                           (when (and orig-type (not= orig-type new-type))
                             (let [cands (->> direct-new-children
                                              (filter (fn [nid]
                                                        (when-let [s (get objects' nid)]
                                                          (= (:type s) orig-type)))))]
                               (when (= 1 (count cands))
                                 (first cands))))
                           instance-root-id (or candidate-instance-root (:id new-root))
                           instance-root    (get objects' instance-root-id)
                           new-subtree-ids  (cfh/get-children-ids objects' instance-root-id)
                           new-all-ids      (into [instance-root-id] new-subtree-ids)
                           id-pairs         (map vector orig-all-ids new-all-ids)
                           changes''
                           ;; Compute per-shape deltas against the reference
                           ;; subtree (root-id) and apply only the differences
                           ;; to the new instance subtree, so we don't blindly
                           ;; overwrite attributes that are the same.
                           (reduce
                            (fn [ch [idx orig-id new-id]]
                              (let [ref-id     (nth ref-all-ids idx nil)
                                    ref-shape  (get objects ref-id)
                                    orig-shape (get objects orig-id)]
                                (if (and ref-shape orig-shape)
                                  (let [;; Style / layout / text props (see `extract-props`)
                                        ref-style   (cts/extract-props ref-shape)
                                        orig-style  (cts/extract-props orig-shape)
                                        style-delta (reduce (fn [m k]
                                                              (let [rv (get ref-style k ::none)
                                                                    ov (get orig-style k ::none)]
                                                                (if (= rv ov)
                                                                  m
                                                                  (assoc m k ov))))
                                                            {}
                                                            (keys orig-style))

                                        ;; Geometry props
                                        ref-geom    (select-keys ref-shape instance-geometry-keys)
                                        orig-geom   (select-keys orig-shape instance-geometry-keys)
                                        geom-delta  (reduce (fn [m k]
                                                              (let [rv (get ref-geom k ::none)
                                                                    ov (get orig-geom k ::none)]
                                                                (if (= rv ov)
                                                                  m
                                                                  (assoc m k ov))))
                                                            {}
                                                            (keys orig-geom))

                                        ;; Text content: if the subtree reference and the
                                        ;; original differ in `:content`, treat the whole
                                        ;; content tree as an override for this instance.
                                        content-delta? (not= (:content ref-shape) (:content orig-shape))]
                                    (-> ch
                                        ;; First patch style/text/layout props using the
                                        ;; canonical helpers so we don't touch structural ids.
                                        (cond-> (seq style-delta)
                                          (pcb/update-shapes
                                           [new-id]
                                           (fn [s objs] (cts/patch-props s style-delta objs))
                                           {:with-objects? true}))
                                        ;; Then patch geometry directly on the instance.
                                        (cond-> (seq geom-delta)
                                          (pcb/update-shapes
                                           [new-id]
                                           (d/patch-object geom-delta)))
                                        ;; Finally, if text content differs between the
                                        ;; reference subtree and the similar subtree,
                                        ;; override the instance content with the original.
                                        (cond-> content-delta?
                                          (pcb/update-shapes
                                           [new-id]
                                           #(assoc % :content (:content orig-shape))))))
                                  ch)))
                            changes'
                            (map-indexed (fn [idx [orig-id new-id]]
                                           [idx orig-id new-id])
                                         id-pairs))]
                       [changes'' (conj acc (:id new-shape))])
                     ;; If the shape does not exist we just skip it
                     [changes acc]))
                 [nil []]
                 similar-ids)

                changes (or changes
                            (-> (pcb/empty-changes it page-id)
                                (pcb/with-objects objects)))

                ;; 2) Delete original similar subtrees
                ;; NOTE: `d/ordered-set` with a single arg treats it as a single
                ;; element, so we must use `into` when we already have a collection.
                ids-to-delete (into (d/ordered-set) similar-ids)
                [all-parents changes]
                (cls/generate-delete-shapes
                 changes
                 fdata
                 page
                 objects
                 ids-to-delete
                 {:allow-altering-copies true})

                ;; 3) Select main instance + new instances
                ;;    Root id is kept as-is; add all new roots.
                sel-ids (into (d/ordered-set) (cons root-id new-root-ids))]

            (rx/of
             (dwu/start-undo-transaction undo-id)
             (dch/commit-changes changes)
             (ptk/data-event :layout/update {:ids all-parents})
             (dwu/commit-undo-transaction undo-id))))))))

(defn componentize-similar-subtrees
  "Turn the subtree rooted at `root-id` into a component, then replace
  the subtrees rooted at `similar-ids` with instances of that component.

  This is implemented in two phases:
  1) Use the existing `dwl/add-component` flow to create a component
     from `root-id` (and obtain its `component-id`).
  2) Using the new `component-id`, instantiate the component once per
     entry in `similar-ids` and delete the old subtrees."
  [root-id similar-ids]
  (dm/assert!
   "expected valid uuid for `root-id`"
   (uuid? root-id))

  (let [similar-ids (vec (or similar-ids []))]
    (ptk/reify ::componentize-similar-subtrees
      ptk/WatchEvent
      (watch [_ _ _]
        (let [id-ref (atom uuid/zero)]
          (rx/concat
           ;; 1) Create component using the existing pipeline
           (rx/of (dwl/add-component id-ref (d/ordered-set root-id)))
           ;; 2) Replace similar subtrees by instances of the new component
           (rx/of (instantiate-similar-subtrees id-ref root-id similar-ids))))))))


