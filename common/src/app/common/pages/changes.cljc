;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages.changes
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bool :as gshb]
   [app.common.pages.common :refer [component-sync-attrs]]
   [app.common.pages.helpers :as cph]
   [app.common.pages.init :as init]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specific helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- without-obj
  "Clear collection from specified obj and without nil values."
  [coll o]
  (into [] (filter #(not= % o)) coll))

(defn vec-without-nils
  [coll]
  (into [] (remove nil?) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Changes Processing Impl

(defmulti process-change (fn [_ change] (:type change)))
(defmulti process-operation (fn [_ op] (:type op)))

(defn process-changes
  ([data items] (process-changes data items true))
  ([data items verify?]
   ;; When verify? false we spec the schema validation. Currently used to make just
   ;; 1 validation even if the changes are applied twice
   (when verify?
     (us/assert ::spec/changes items))

   (let [result (reduce #(or (process-change %1 %2) %1) data items)]
     ;; Validate result shapes (only on the backend)
     #?(:clj
        (doseq [page-id (into #{} (map :page-id) items)]
          (let [page (get-in result [:pages-index page-id])]
            (doseq [[id shape] (:objects page)]
              (when-not (= shape (get-in data [:pages-index page-id :objects id]))
                ;; If object has change verify is correct
                (us/verify ::spec/shape shape))))))

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
  (letfn [(update-parent-shapes [shapes]
            ;; Ensure that shapes is always a vector.
            (let [shapes (into [] shapes)]
              (cond
                (some #{id} shapes)
                shapes

                (nil? index)
                (if (= :frame (:type obj))
                  (into [id] shapes)
                  (conj shapes id))

                :else
                (cph/insert-at-index shapes index [id]))))

          (update-parent [parent]
            (-> parent
                (update :shapes update-parent-shapes)
                (update :shapes vec-without-nils)
                (cond-> (and (:shape-ref parent)
                             (not= (:id parent) frame-id)
                             (not ignore-touched))
                  (-> (update :touched cph/set-touched-group :shapes-group)
                      (dissoc :remote-synced?)))))

          (update-objects [objects parent-id]
            (if (and (or (nil? parent-id) (contains? objects parent-id))
                     (or (nil? frame-id) (contains? objects frame-id)))
              (-> objects
                  (assoc id (-> obj
                                (assoc :frame-id frame-id)
                                (assoc :parent-id parent-id)
                                (assoc :id id)))
                  (update parent-id update-parent))
              objects))

          (update-container [data]
            (let [parent-id (or parent-id frame-id)]
              (update data :objects update-objects parent-id)))]

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
  (letfn [(delete-from-parent [parent]
            (let [parent (update parent :shapes without-obj id)]
              (cond-> parent
                (and (:shape-ref parent)
                     (not ignore-touched))
                (-> (update :touched cph/set-touched-group :shapes-group)
                    (dissoc :remote-synced?)))))

          (delete-from-objects [objects]
            (if-let [target (get objects id)]
              (let [parent-id (or (:parent-id target)
                                  (:frame-id target))
                    children  (cph/get-children id objects)]
                (-> (reduce dissoc objects children)
                    (dissoc id)
                    (d/update-when parent-id delete-from-parent)))
              objects))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] delete-from-objects)
      (d/update-in-when data [:components component-id :objects] delete-from-objects))))

;; reg-objects operation "regenerates" the geometry and selrect of the parent groups
(defmethod process-change :reg-objects
  [data {:keys [page-id component-id shapes]}]
  (letfn [(reg-objects [objects]
            (reduce #(d/update-when %1 %2 update-group %1) objects
                    (sequence (comp
                               (mapcat #(cons % (cph/get-parents % objects)))
                               (map #(get objects %))
                               (filter #(contains? #{:group :bool} (:type %)))
                               (map :id)
                               (distinct))
                              shapes)))
          (set-mask-selrect [group children]
            (let [mask (first children)]
              (-> group
                  (merge (select-keys mask [:selrect :points]))
                  (assoc :x (-> mask :selrect :x)
                         :y (-> mask :selrect :y)
                         :width (-> mask :selrect :width)
                         :height (-> mask :selrect :height)
                         :flip-x (-> mask :flip-x)
                         :flip-y (-> mask :flip-y)))))
          (update-group [group objects]
            (let [children (->> group :shapes (map #(get objects %)))]
              (cond
                ;; If the group is empty we don't make any changes. Should be removed by a later process
                (empty? children)
                group

                (= :bool (:type group))
                (gshb/update-bool-selrect group children objects)

                (:masked-group? group)
                (set-mask-selrect group children)

                :else
                (gsh/update-group-selrect group children))))]

    (if page-id
      (d/update-in-when data [:pages-index page-id :objects] reg-objects)
      (d/update-in-when data [:components component-id :objects] reg-objects))))

(defmethod process-change :mov-objects
  [data {:keys [parent-id shapes index page-id component-id ignore-touched]}]
  (letfn [(is-valid-move? [objects shape-id]
            (let [invalid-targets (cph/calculate-invalid-targets shape-id objects)]
              (and (not (invalid-targets parent-id))
                   (cph/valid-frame-target shape-id parent-id objects))))

          (insert-items [prev-shapes index shapes]
            (let [prev-shapes (or prev-shapes [])]
              (if index
                (cph/insert-at-index prev-shapes index shapes)
                (cph/append-at-the-end prev-shapes shapes))))

          (check-insert-items [prev-shapes parent index shapes]
            (if-not (:masked-group? parent)
              (insert-items prev-shapes index shapes)
              ;; For masked groups, the first shape is the mask
              ;; and it cannot be moved.
              (let [mask-id         (first prev-shapes)
                    other-ids       (rest prev-shapes)
                    not-mask-shapes (without-obj shapes mask-id)
                    new-index       (if (nil? index) nil (max (dec index) 0))
                    new-shapes      (insert-items other-ids new-index not-mask-shapes)]
                (d/concat [mask-id] new-shapes))))

          (add-to-parent [parent index shapes]
            (let [parent (-> parent
                             (update :shapes check-insert-items parent index shapes)
                             ;; We need to ensure that no `nil` in the
                             ;; shapes list after adding all the
                             ;; incoming shapes to the parent.
                             (update :shapes vec-without-nils))]
              (cond-> parent
                (and (:shape-ref parent) (= (:type parent) :group) (not ignore-touched))
                (-> (update :touched cph/set-touched-group :shapes-group)
                    (dissoc :remote-synced?)))))

          (remove-from-old-parent [cpindex objects shape-id]
            (let [prev-parent-id (get cpindex shape-id)]
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
                      (d/update-in-when [pid :shapes] without-obj sid)
                      (d/update-in-when [pid :shapes] vec-without-nils)
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

                  ;; Create a index of shape ids pointing to the
                  ;; corresponding parents; used mainly for update old
                  ;; parents after move operation.
                  cpindex  (reduce (fn [index id]
                                     (let [obj (get objects id)]
                                       (assoc! index id (:parent-id obj))))
                                   (transient {})
                                   (keys objects))
                  cpindex  (persistent! cpindex)

                  parent   (get objects parent-id)
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
                  (reduce (partial remove-from-old-parent cpindex) $ shapes)

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
              :hint "name or page should be provided, never both"))
  (letfn [(conj-if-not-exists [pages id]
            (cond-> pages
              (not (d/seek #(= % id) pages))
              (conj id)))]
    (if (and (string? name) (uuid? id))
      (let [page (assoc init/empty-page-data
                        :id id
                        :name name)]
        (-> data
            (update :pages conj-if-not-exists id)
            (update :pages-index assoc id page)))

      (-> data
          (update :pages conj-if-not-exists (:id page))
          (update :pages-index assoc (:id page) page)))))

(defmethod process-change :mod-page
  [data {:keys [id name]}]
  (d/update-in-when data [:pages-index id] assoc :name name))

(defmethod process-change :del-page
  [data {:keys [id]}]
  (-> data
      (update :pages (fn [pages] (filterv #(not= % id) pages)))
      (update :pages-index dissoc id)))

(defmethod process-change :mov-page
  [data {:keys [id index]}]
  (update data :pages cph/insert-at-index index [id]))

(defmethod process-change :add-color
  [data {:keys [color]}]
  (update data :colors assoc (:id color) color))

(defmethod process-change :mod-color
  [data {:keys [color]}]
  (d/assoc-in-when data [:colors (:id color)] color))

(defmethod process-change :del-color
  [data {:keys [id]}]
  (update data :colors dissoc id))

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
  [data {:keys [id name path shapes]}]
  (assoc-in data [:components id]
            {:id id
             :name name
             :path path
             :objects (d/index-by :id shapes)}))

(defmethod process-change :mod-component
  [data {:keys [id name path objects]}]
  (update-in data [:components id]
             #(cond-> %
                  (some? name)
                  (assoc :name name)

                  (some? path)
                  (assoc :path path)

                  (some? objects)
                  (assoc :objects objects))))

(defmethod process-change :del-component
  [data {:keys [id]}]
  (d/dissoc-in data [:components id]))

;; -- Typography

(defmethod process-change :add-typography
  [data {:keys [typography]}]
  (update data :typographies assoc (:id typography) typography))

(defmethod process-change :mod-typography
  [data {:keys [typography]}]
  (d/update-in-when data [:typographies (:id typography)] merge typography))

(defmethod process-change :del-typography
  [data {:keys [id]}]
  (update data :typographies dissoc id))

;; -- Operations

(defmethod process-operation :set
  [shape op]
  (let [attr       (:attr op)
        val        (:val op)
        ignore     (:ignore-touched op)
        ignore-geometry (:ignore-geometry op)
        shape-ref  (:shape-ref shape)
        group      (get component-sync-attrs attr)
        root-name? (and (= group :name-group)
                        (:component-root? shape))]

    (cond-> shape
      ;; Depending on the origin of the attribute change, we need or not to
      ;; set the "touched" flag for the group the attribute belongs to.
      ;; In some cases we need to ignore touched only if the attribute is
      ;; geometric (position, width or transformation).
      (and shape-ref group (not ignore) (not= val (get shape attr))
           (not root-name?)
           (not (and ignore-geometry
                     (and (= group :geometry-group)
                          (not (#{:width :height} attr))))))
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

