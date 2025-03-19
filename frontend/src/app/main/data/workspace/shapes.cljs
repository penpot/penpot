;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.logic.shapes :as cls]
   [app.common.schema :as sm]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dc]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private update-layout-attr? #{:hidden})

(defn- add-undo-group
  [changes state]
  (let [undo            (:workspace-undo state)
        items           (:items undo)
        index           (or (:index undo) (dec (count items)))
        prev-item       (when-not (or (empty? items) (= index -1))
                          (get items index))
        undo-group      (:undo-group prev-item)
        add-undo-group? (and
                         (not (nil? undo-group))
                         (= (get-in changes [:redo-changes 0 :type]) :mod-obj)
                         (= (get-in prev-item [:redo-changes 0 :type]) :add-obj)
                         (contains? (:tags prev-item) :alt-duplication))] ;; This is a copy-and-move with mouse+alt

    (cond-> changes add-undo-group? (assoc :undo-group undo-group))))

(defn update-shapes
  ([ids update-fn] (update-shapes ids update-fn nil))
  ([ids update-fn {:keys [reg-objects? save-undo? stack-undo? attrs ignore-tree page-id ignore-touched undo-group with-objects? changed-sub-attr]
                   :or {reg-objects? false save-undo? true stack-undo? false ignore-touched false with-objects? false}}]

   (assert (sm/check-coll-of-uuid ids))
   (assert (fn? update-fn))

   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id   (or page-id (:current-page-id state))
             objects   (dsh/lookup-page-objects state page-id)
             ids       (into [] (filter some?) ids)

             update-layout-ids
             (->> ids
                  (map (d/getf objects))
                  (filter #(some update-layout-attr? (pcb/changed-attrs % objects update-fn {:attrs attrs :with-objects? with-objects?})))
                  (map :id))

             changes (-> (pcb/empty-changes it page-id)
                         (pcb/set-save-undo? save-undo?)
                         (pcb/set-stack-undo? stack-undo?)
                         (cls/generate-update-shapes ids
                                                     update-fn
                                                     objects
                                                     {:attrs attrs
                                                      :changed-sub-attr changed-sub-attr
                                                      :ignore-tree ignore-tree
                                                      :ignore-touched ignore-touched
                                                      :with-objects? with-objects?})
                         (cond-> undo-group
                           (pcb/set-undo-group undo-group)))

             changes (add-undo-group changes state)]
         (rx/concat
          (if (seq (:redo-changes changes))
            (let [changes (cond-> changes reg-objects? (pcb/resize-parents ids))]
              (rx/of (dch/commit-changes changes)))
            (rx/empty))

          ;; Update layouts for properties marked
          (if (d/not-empty? update-layout-ids)
            (rx/of (ptk/data-event :layout/update {:ids update-layout-ids}))
            (rx/empty))))))))

(defn add-shape
  ([shape]
   (add-shape shape {}))
  ([shape {:keys [no-select? no-update-layout?]}]

   (dm/assert!
    "expected valid shape"
    (cts/check-shape! shape))

   (ptk/reify ::add-shape
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (dsh/lookup-page-objects state page-id)

             [shape changes]
             (-> (pcb/empty-changes it page-id)
                 (pcb/with-objects objects)
                 (cfsh/prepare-add-shape shape objects))

             changes (cond-> changes
                       (cfh/text-shape? shape)
                       (pcb/set-undo-group (:id shape)))

             undo-id (js/Symbol)]

         (rx/concat
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (when-not no-update-layout?
                   (ptk/data-event :layout/update {:ids [(:parent-id shape)]}))
                 (when-not no-select?
                   (dws/select-shapes (d/ordered-set (:id shape))))
                 (dwu/commit-undo-transaction undo-id))
          (when (cfh/text-shape? shape)
            (->> (rx/of (dwe/start-edition-mode (:id shape)))
                 (rx/observe-on :async)))
          (when (cfh/frame-shape? shape)
            (rx/of (ptk/event ::ev/event {::ev/name "add-frame"})))))))))

(defn move-shapes-into-frame
  [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            shapes  (->> shapes
                         (remove #(dm/get-in objects [% :blocked]))
                         (cfh/order-by-indexed-shapes objects))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects))

            changes (cfsh/prepare-move-shapes-into-frame changes frame-id shapes objects true)]

        (if (some? changes)
          (rx/of (dch/commit-changes changes))
          (rx/empty))))))

(declare update-shape-flags)

(defn delete-shapes
  ([ids] (delete-shapes nil ids {}))
  ([page-id ids] (delete-shapes page-id ids {}))
  ([page-id ids options]
   (assert (sm/check-set-of-uuid ids))

   (ptk/reify ::delete-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [file-id       (:current-file-id state)
             page-id       (or page-id (:current-page-id state))

             fdata         (dsh/lookup-file-data state file-id)
             page          (dsh/get-page fdata page-id)
             objects       (:objects page)

             components-v2 (features/active-feature? state "components/v2")
             undo-id (or (:undo-id options) (js/Symbol))
             [all-parents changes] (-> (pcb/empty-changes it (:id page))
                                       (cls/generate-delete-shapes fdata page objects ids
                                                                   {:components-v2 components-v2
                                                                    :ignore-touched (:component-swap options)
                                                                    :undo-group (:undo-group options)
                                                                    :undo-id undo-id}))]

         (rx/of (dwu/start-undo-transaction undo-id)
                (dc/detach-comment-thread ids)
                (dch/commit-changes changes)
                (ptk/data-event :layout/update {:ids all-parents :undo-group (:undo-group options)})
                (dwu/commit-undo-transaction undo-id)))))))

(defn create-and-add-shape
  [type frame-x frame-y {:keys [width height] :as attrs}]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state _]
      (let [vbc       (dsh/get-viewport-center state)
            x         (:x attrs (- (:x vbc) (/ width 2)))
            y         (:y attrs (- (:y vbc) (/ height 2)))
            page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            frame-id  (-> (dsh/lookup-page-objects state page-id)
                          (ctst/top-nested-frame {:x frame-x :y frame-y}))

            selected  (dsh/lookup-selected state)
            base      (cfh/get-base-shape objects selected)

            parent-id (if (or (and (= 1 (count selected))
                                   (cfh/frame-shape? (get objects (first selected))))
                              (empty? selected))
                        frame-id
                        (:parent-id base))

            ;; If the parent-id or the frame-id are component-copies, we need to get the first not copy parent
            parent-id (:id (ctn/get-first-not-copy-parent objects parent-id))   ;; We don't want to change the structure of component copies
            frame-id  (:id (ctn/get-first-not-copy-parent objects frame-id))


            shape     (cts/setup-shape
                       (-> attrs
                           (assoc :type type)
                           (assoc :x x)
                           (assoc :y y)
                           (assoc :frame-id frame-id)
                           (assoc :parent-id parent-id)))]

        (rx/of (add-shape shape))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Artboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-artboard-from-selection
  ([]
   (create-artboard-from-selection nil))
  ([id]
   (create-artboard-from-selection id nil))
  ([id parent-id]
   (create-artboard-from-selection id parent-id nil))
  ([id parent-id index]
   (create-artboard-from-selection id parent-id index nil))
  ([id parent-id index name]
   (ptk/reify ::create-artboard-from-selection
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id      (:current-page-id state)
             objects      (dsh/lookup-page-objects state page-id)
             selected     (->> (dsh/lookup-selected state)
                               (cfh/clean-loops objects)
                               (remove #(ctn/has-any-copy-parent? objects (get objects %)))
                               (remove #(->> %
                                             (get objects)
                                             (ctc/is-variant?))))

             changes      (-> (pcb/empty-changes it page-id)
                              (pcb/with-objects objects))

             [frame-shape changes]
             (cfsh/prepare-create-artboard-from-selection changes
                                                          id
                                                          parent-id
                                                          objects
                                                          selected
                                                          index
                                                          name
                                                          false)

             undo-id  (js/Symbol)]

         (when changes
           (rx/of
            (dwu/start-undo-transaction undo-id)
            (dch/commit-changes changes)
            (dws/select-shapes (d/ordered-set (:id frame-shape)))
            (ptk/data-event :layout/update {:ids [(:id frame-shape)]})
            (dwu/commit-undo-transaction undo-id))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape Flags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-shape-flags
  [ids {:keys [blocked hidden undo-group] :as flags}]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))

  (dm/assert!
   "expected valid shape-attrs value for `flags`"
   (cts/check-shape-attrs! flags))

  (ptk/reify ::update-shape-flags
    ptk/WatchEvent
    (watch [_ state _]
      (let [update-fn
            (fn [obj]
              (cond-> obj
                (boolean? blocked) (assoc :blocked blocked)
                (boolean? hidden) (assoc :hidden hidden)))
            objects (dsh/lookup-page-objects state)
            ;; We have change only the hidden behaviour, to hide only the
            ;; selected shape, block behaviour remains the same.
            ids     (if (boolean? blocked)
                      (into ids (->> ids (mapcat #(cfh/get-children-ids objects %))))
                      ids)]
        (rx/of (update-shapes ids update-fn {:attrs #{:blocked :hidden} :undo-group undo-group}))))))

(defn toggle-visibility-selected
  []
  (ptk/reify ::toggle-visibility-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (update-shapes selected #(update % :hidden not)))))))

(defn toggle-lock-selected
  []
  (ptk/reify ::toggle-lock-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (update-shapes selected #(update % :blocked not)))))))


;; FIXME: this need to be refactored

(defn toggle-file-thumbnail-selected
  []
  (ptk/reify ::toggle-file-thumbnail-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected   (dsh/lookup-selected state)
            fdata      (dsh/lookup-file-data state)
            pages      (-> fdata :pages-index vals)
            undo-id  (js/Symbol)]

        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         ;; First: clear the `:use-for-thumbnail` flag from all not
         ;; selected frames.
         (rx/from
          (->> pages
               (mapcat
                (fn [{:keys [objects id] :as page}]
                  (->> (ctst/get-frames objects)
                       (sequence
                        (comp (filter :use-for-thumbnail)
                              (map :id)
                              (remove selected)
                              (map (partial vector id)))))))
               (d/group-by first second)
               (map (fn [[page-id frame-ids]]
                      (update-shapes frame-ids #(dissoc % :use-for-thumbnail) {:page-id page-id})))))

         ;; And finally: toggle the flag value on all the selected shapes
         (rx/of (update-shapes selected #(update % :use-for-thumbnail not))
                (dwu/commit-undo-transaction undo-id)))))))
