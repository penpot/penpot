;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.changes
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.common :refer [component-sync-attrs]]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.pages.changes-spec :as pcs]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Changes Processing Impl

(defn validate-shapes!
  [data objects items]
  (letfn [(validate-shape! [[page-id {:keys [id] :as shape}]]
            (when-not (= shape (dm/get-in data [:pages-index page-id :objects id]))
              ;; If object has change verify is correct
              (us/verify ::cts/shape shape)))]
    (let [lookup (d/getf objects)]
      (->> (into #{} (map :page-id) items)
           (mapcat (fn [page-id]
                     (filter #(= page-id (:page-id %)) items)))
           (mapcat (fn [{:keys [type id page-id] :as item}]
                     (sequence
                      (comp (keep lookup)
                            (map (partial vector page-id)))
                      (case type
                        (:add-obj :mod-obj :del-obj) (cons id nil)
                        (:mov-objects :reg-objects)  (:shapes item)
                        nil))))
           (run! validate-shape!)))))

(defmulti process-change (fn [_ change] (:type change)))
(defmulti process-operation (fn [_ op] (:type op)))

(defn process-changes
  ([data items]
   (process-changes data items true))

  ([data items verify?]
   ;; When verify? false we spec the schema validation. Currently used to make just
   ;; 1 validation even if the changes are applied twice
   (when verify?
     (us/assert ::pcs/changes items))

   (let [result (reduce #(or (process-change %1 %2) %1) data items)]
     ;; Validate result shapes (only on the backend)
     #?(:clj (validate-shapes! data result items))
     result)))

(defmethod process-change :set-option
  [data {:keys [page-id option value]}]
  (d/update-in-when data [:pages-index page-id]
                    (fn [data]
                      (let [path (if (seqable? option) option [option])]
                        (if value
                          (assoc-in data (into [:options] path) value)
                          (assoc data :options (d/dissoc-in (:options data) path)))))))

(defmethod process-change :add-obj
  [data {:keys [id obj page-id component-id frame-id parent-id index ignore-touched]}]
  (let [update-container
        (fn [container]
          (ctst/add-shape id obj container frame-id parent-id index ignore-touched))]

    (if page-id
      (d/update-in-when data [:pages-index page-id] update-container)
      (d/update-in-when data [:components component-id] update-container))))

(defmethod process-change :mod-obj
  [data {:keys [id page-id component-id operations]}]
  (let [update-fn (fn [objects]
                    (if-let [obj (get objects id)]
                      (let [result (reduce process-operation obj operations)]
                        (assoc objects id result))
                      objects))]
    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] update-fn)
      (d/update-in-when data [:components component-id :objects] update-fn))))

(defmethod process-change :del-obj
  [data {:keys [page-id component-id id ignore-touched]}]
  (if page-id
    (d/update-in-when data [:pages-index page-id] ctst/delete-shape id ignore-touched)
    (d/update-in-when data [:components component-id] ctst/delete-shape id ignore-touched)))

;; reg-objects operation "regenerates" the geometry and selrect of the parent groups
(defmethod process-change :reg-objects
  [data {:keys [page-id component-id shapes]}]
  ;; FIXME: Improve performance
  (letfn [(reg-objects [objects]
            (let [lookup    (d/getf objects)
                  update-fn #(d/update-when %1 %2 update-group %1)
                  xform     (comp
                             (mapcat #(cons % (cph/get-parent-ids objects %)))
                             (filter #(contains? #{:group :bool} (-> % lookup :type)))
                             (distinct))]

              (->> (sequence xform shapes)
                   (reduce update-fn objects))))

          (set-mask-selrect [group children]
            (let [mask (first children)]
              (-> group
                  (assoc :selrect (-> mask :selrect))
                  (assoc :points  (-> mask :points))
                  (assoc :x       (-> mask :selrect :x))
                  (assoc :y       (-> mask :selrect :y))
                  (assoc :width   (-> mask :selrect :width))
                  (assoc :height  (-> mask :selrect :height))
                  (assoc :flip-x  (-> mask :flip-x))
                  (assoc :flip-y  (-> mask :flip-y)))))

          (update-group [group objects]
            (let [lookup   (d/getf objects)
                  children (->> group :shapes (map lookup))]
              (cond
                ;; If the group is empty we don't make any changes. Will be removed by a later process
                (empty? children)
                group

                (= :bool (:type group))
                (gsh/update-bool-selrect group children objects)

                (:masked-group? group)
                (set-mask-selrect group children)

                :else
                (gsh/update-group-selrect group children))))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] reg-objects)
      (d/update-in-when data [:components component-id :objects] reg-objects))))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index page-id component-id ignore-touched after-shape]}]
  (letfn [(calculate-invalid-targets [objects shape-id]
            (let [reduce-fn #(into %1 (calculate-invalid-targets objects %2))]
              (->> (get-in objects [shape-id :shapes])
                   (reduce reduce-fn #{shape-id}))))

          (is-valid-move? [objects shape-id]
            (let [invalid-targets (calculate-invalid-targets objects shape-id)]
              (and (contains? objects shape-id)
                   (not (invalid-targets parent-id))
                   #_(cph/valid-frame-target? objects parent-id shape-id))))

          (insert-items [prev-shapes index shapes]
            (let [prev-shapes (or prev-shapes [])]
              (if index
                (d/insert-at-index prev-shapes index shapes)
                (cph/append-at-the-end prev-shapes shapes))))

          (add-to-parent [parent index shapes]
            (let [parent (-> parent
                             (update :shapes insert-items index shapes)
                             ;; We need to ensure that no `nil` in the
                             ;; shapes list after adding all the
                             ;; incoming shapes to the parent.
                             (update :shapes d/vec-without-nils))]
              (cond-> parent
                (and (:shape-ref parent) (= (:type parent) :group) (not ignore-touched))
                (-> (update :touched cph/set-touched-group :shapes-group)
                    (dissoc :remote-synced?)))))

          (remove-from-old-parent [old-objects objects shape-id]
            (let [prev-parent-id (dm/get-in old-objects [shape-id :parent-id])]
              ;; Do nothing if the parent id of the shape is the same as
              ;; the new destination target parent id.
              (if (= prev-parent-id parent-id)
                objects
                (let [sid        shape-id
                      pid        prev-parent-id
                      obj        (get objects pid)
                      component? (and (:shape-ref obj)
                                      (= (:type obj) :group)
                                      (not ignore-touched))]

                  (-> objects
                      (d/update-in-when [pid :shapes] d/without-obj sid)
                      (d/update-in-when [pid :shapes] d/vec-without-nils)
                      (cond-> component? (d/update-when pid #(-> %
                                                                 (update :touched cph/set-touched-group :shapes-group)
                                                                 (dissoc :remote-synced?)))))))))

          (update-parent-id [objects id]
            (-> objects
                (d/update-when id assoc :parent-id parent-id)))

          ;; Updates the frame-id references that might be outdated
          (assign-frame-id [frame-id objects id]
            (let [objects (d/update-when objects id assoc :frame-id frame-id)
                  obj     (get objects id)]
              (cond-> objects
                ;; If we moving frame, the parent frame is the root
                ;; and we DO NOT NEED update children because the
                ;; children will point correctly to the frame what we
                ;; are currently moving
                (not= :frame (:type obj))
                (as-> $$ (reduce (partial assign-frame-id frame-id) $$ (:shapes obj))))))



          (move-objects [objects]
            (let [valid?   (every? (partial is-valid-move? objects) shapes)
                  parent   (get objects parent-id)
                  after-shape-index (d/index-of (:shapes parent) after-shape)
                  index (if (nil? after-shape-index) index (inc after-shape-index))
                  frame-id (if (= :frame (:type parent))
                             (:id parent)
                             (:frame-id parent))]

              (if (and valid? (seq shapes))
                (as-> objects $
                  ;; Add the new shapes to the parent object.
                  (d/update-when $ parent-id #(add-to-parent % index shapes))

                  ;; Update each individual shape link to the new parent
                  (reduce update-parent-id $ shapes)

                  ;; Analyze the old parents and clear the old links
                  ;; only if the new parent is different form old
                  ;; parent.
                  (reduce (partial remove-from-old-parent objects) $ shapes)

                  ;; Ensure that all shapes of the new parent has a
                  ;; correct link to the topside frame.
                  (reduce (partial assign-frame-id frame-id) $ shapes))
                objects)))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] move-objects)
      (d/update-in-when data [:components component-id :objects] move-objects))))

(defmethod process-change :add-page
  [data {:keys [id name page]}]
  (when (and id name page)
    (ex/raise :type :conflict
              :hint "id+name or page should be provided, never both"))
  (let [page (if (and (string? name) (uuid? id))
               (ctp/make-empty-page id name)
               page)]
    (ctpl/add-page data page)))

(defmethod process-change :mod-page
  [data {:keys [id name]}]
  (d/update-in-when data [:pages-index id] assoc :name name))

(defmethod process-change :del-page
  [data {:keys [id]}]
  (ctpl/delete-page data id))

(defmethod process-change :mov-page
  [data {:keys [id index]}]
  (update data :pages d/insert-at-index index [id]))

(defmethod process-change :add-color
  [data {:keys [color]}]
  (ctcl/add-color data color))

(defmethod process-change :mod-color
  [data {:keys [color]}]
  (d/assoc-in-when data [:colors (:id color)] color))

(defmethod process-change :del-color
  [data {:keys [id]}]
  (ctcl/delete-color data id))

(defmethod process-change :add-recent-color
  [data {:keys [color]}]
  ;; Moves the color to the top of the list and then truncates up to 15
  (update data :recent-colors (fn [rc]
                                (let [rc (conj (filterv (comp not #{color}) (or rc [])) color)]
                                  (if (> (count rc) 15)
                                    (subvec rc 1)
                                    rc)))))

;; -- Media

(defmethod process-change :add-media
  [data {:keys [object]}]
  (update data :media assoc (:id object) object))

(defmethod process-change :mod-media
  [data {:keys [object]}]
  (d/update-in-when data [:media (:id object)] merge object))

(defmethod process-change :del-media
  [data {:keys [id]}]
  (update data :media dissoc id))

;; -- Components

(defmethod process-change :add-component
  [data params]
  (ctkl/add-component data params))

(defmethod process-change :mod-component
  [data params]
  (ctkl/mod-component data params))

(defmethod process-change :del-component
  [data {:keys [id skip-undelete?]}]
  (ctf/delete-component data id skip-undelete?))

(defmethod process-change :restore-component
  [data {:keys [id page-id]}]
  (ctf/restore-component data id page-id))

(defmethod process-change :purge-component
  [data {:keys [id]}]
  (ctf/purge-component data id))

;; -- Typography

(defmethod process-change :add-typography
  [data {:keys [typography]}]
  (ctyl/add-typography data typography))

(defmethod process-change :mod-typography
  [data {:keys [typography]}]
  (d/update-in-when data [:typographies (:id typography)] merge typography))

(defmethod process-change :del-typography
  [data {:keys [id]}]
  (ctyl/delete-typography data id))

;; === Operations

(defmethod process-operation :set
  [shape op]
  (let [attr            (:attr op)
        group           (get component-sync-attrs attr)
        val             (:val op)
        shape-val       (get shape attr)
        ignore          (:ignore-touched op)
        ignore-geometry (:ignore-geometry op)
        is-geometry?    (and (or (= group :geometry-group)
                                 (and (= group :content-group) (= (:type shape) :path)))
                             (not (#{:width :height} attr))) ;; :content in paths are also considered geometric
                        ;; TODO: the check of :width and :height probably may be removed
                        ;;       after the check added in data/workspace/modifiers/check-delta
                        ;;       function. Better check it and test toroughly when activating
                        ;;       components-v2 mode.
        shape-ref       (:shape-ref shape)
        root-name?      (and (= group :name-group)
                             (:component-root? shape))

        ;; For geometric attributes, there are cases in that the value changes
        ;; slightly (e.g. when rounding to pixel, or when recalculating text
        ;; positions in different zoom levels). To take this into account, we
        ;; ignore geometric changes smaller than 1 pixel.
        equal? (if is-geometry?
                 (gsh/close-attrs? attr val shape-val 1)
                 (gsh/close-attrs? attr val shape-val))]

    (cond-> shape
      ;; Depending on the origin of the attribute change, we need or not to
      ;; set the "touched" flag for the group the attribute belongs to.
      ;; In some cases we need to ignore touched only if the attribute is
      ;; geometric (position, width or transformation).
      (and shape-ref group (not ignore) (not equal?)
           (not root-name?)
           (not (and ignore-geometry is-geometry?)))
      (->
        (update :touched cph/set-touched-group group)
        (dissoc :remote-synced?))

      (nil? val)
      (dissoc attr)

      (some? val)
      (assoc attr val))))

(defmethod process-operation :set-touched
  [shape op]
  (let [touched (:touched op)
        shape-ref (:shape-ref shape)]
    (if (or (nil? shape-ref) (nil? touched) (empty? touched))
      (dissoc shape :touched)
      (assoc shape :touched touched))))

(defmethod process-operation :set-remote-synced
  [shape op]
  (let [remote-synced? (:remote-synced? op)
        shape-ref (:shape-ref shape)]
    (if (or (nil? shape-ref) (not remote-synced?))
      (dissoc shape :remote-synced?)
      (assoc shape :remote-synced? true))))

(defmethod process-operation :default
  [_ op]
  (ex/raise :type :not-implemented
            :code :operation-not-implemented
            :context {:type (:type op)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component changes detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Analyze one change and checks if if modifies the main instance of
;; any component, so that it needs to be synced immediately to the
;; main component. Return the ids of the components that need sync.

(defmulti components-changed (fn [_ change] (:type change)))

(defmethod components-changed :mod-obj
  [file-data {:keys [id page-id _component-id operations]}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)
          shape-and-parents (map #(ctn/get-shape page %)
                                 (into [id] (cph/get-parent-ids (:objects page) id)))
          need-sync? (fn [operation]
                       ; We need to trigger a sync if the shape has changed any
                       ; attribute that participates in components synchronization.
                       (and (= (:type operation) :set)
                            (component-sync-attrs (:attr operation))))
          any-sync? (some need-sync? operations)]
      (when any-sync?
        (let [xform (comp (filter :main-instance?) ; Select shapes that are main component instances
                          (map :id))]
          (into #{} xform shape-and-parents))))))

(defmethod components-changed :default
  [_ _]
  nil)

