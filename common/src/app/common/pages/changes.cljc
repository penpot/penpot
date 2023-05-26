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
   [app.common.schema :as sm]
   [app.common.schema.desc-native :as smd]
   [app.common.spec :as us]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]
   [app.common.types.typography :as ctt]
   [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/def! ::operation
  [:multi {:dispatch :type :title "Operation" ::smd/simplified true}
   [:set
    [:map {:title "SetOperation"}
     [:type [:= :set]]
     [:attr :keyword]
     [:val :any]
     [:ignore-touched {:optional true} :boolean]
     [:ignore-geometry {:optional true} :boolean]]]
   [:set-touched
    [:map {:title "SetTouchedOperation"}
     [:type [:= :set-touched]]
     [:touched [:maybe [:set :keyword]]]]]
   [:set-remote-synced
    [:map {:title "SetRemoteSyncedOperation"}
     [:type [:= :set-remote-synced]]
     [:remote-synced? [:maybe :boolean]]]]])

(sm/def! ::change
  [:schema
   [:multi {:dispatch :type :title "Change" ::smd/simplified true}
    [:set-option
     [:map {:title "SetOptionChange"}
      [:type [:= :set-option]]
      [:page-id ::sm/uuid]
      [:option [:union
                [:keyword]
                [:vector {:gen/max 10} :keyword]]]
      [:value :any]]]

    [:add-obj
     [:map {:title "AddObjChange"}
      [:type [:= :add-obj]]
      [:id ::sm/uuid]
      [:obj [:map-of {:gen/max 10} :keyword :any]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:frame-id {:optional true} ::sm/uuid]
      [:parent-id {:optional true} ::sm/uuid]
      [:index {:optional true} [:maybe :int]]
      [:ignore-touched {:optional true} :boolean]
      ]]

    [:mod-obj
     [:map {:title "ModObjChange"}
      [:type [:= :mod-obj]]
      [:id ::sm/uuid]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:operations [:vector {:gen/max 5} ::operation]]]]

    [:del-obj
     [:map {:title "DelObjChange"}
      [:type [:= :del-obj]]
      [:id ::sm/uuid]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:ignore-touched {:optional true} :boolean]]]

    [:mov-objects
     [:map {:title "MovObjectsChange"}
      [:type [:= :mov-objects]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:ignore-touched {:optional true} :boolean]
      [:parent-id ::sm/uuid]
      [:shapes :any]
      [:index {:optional true} :int]
      [:after-shape {:optional true} :any]]]

    [:add-page
     [:map {:title "AddPageChange"}
      [:type [:= :add-page]]
      [:id {:optional true} ::sm/uuid]
      [:name {:optional true} :string]
      [:page {:optional true} :any]]]

    [:mod-page
     [:map {:title "ModPageChange"}
      [:type [:= :mod-page]]
      [:id ::sm/uuid]
      [:name :string]]]

    [:del-page
     [:map {:title "DelPageChange"}
      [:type [:= :del-page]]
      [:id ::sm/uuid]]]

    [:mov-page
     [:map {:title "MovPageChange"}
      [:type [:= :mov-page]]
      [:id ::sm/uuid]
      [:index :int]]]

    [:reg-objects
     [:map {:title "RegObjectsChange"}
      [:type [:= :reg-objects]]
      [:page-id {:optional true} ::sm/uuid]
      [:component-id {:optional true} ::sm/uuid]
      [:shapes [:vector {:gen/max 5} ::sm/uuid]]]]

    [:add-color
     [:map {:title "AddColorChange"}
      [:type [:= :add-color]]
      [:color :any]]]

    [:mod-color
     [:map {:title "ModColorChange"}
      [:type [:= :mod-color]]
      [:color :any]]]

    [:del-color
     [:map {:title "DelColorChange"}
      [:type [:= :del-color]]
      [:id ::sm/uuid]]]

    [:add-recent-color
     [:map {:title "AddRecentColorChange"}
      [:type [:= :add-recent-color]]
      [:color :any]]]

    [:add-media
     [:map {:title "AddMediaChange"}
      [:type [:= :add-media]]
      [:object ::ctf/media-object]]]

    [:mod-media
     [:map {:title "ModMediaChange"}
      [:type [:= :mod-media]]
      [:object ::ctf/media-object]]]

    [:del-media
     [:map {:title "DelMediaChange"}
      [:type [:= :del-media]]
      [:id ::sm/uuid]]]

    [:add-component
     [:map {:title "AddComponentChange"}
      [:type [:= :add-component]]
      [:id ::sm/uuid]
      [:name :string]
      [:shapes {:optional true} [:vector {:gen/max 3} :any]]
      [:path {:optional true} :string]]]

    [:mod-component
     [:map {:title "ModCompoenentChange"}
      [:type [:= :mod-component]]
      [:id ::sm/uuid]
      [:shapes {:optional true} [:vector {:gen/max 3} :any]]
      [:name {:optional true} :string]]]

    [:del-component
     [:map {:title "DelComponentChange"}
      [:type [:= :del-component]]
      [:id ::sm/uuid]
      [:skip-undelete? {:optional true} :boolean]]]

    [:restore-component
     [:map {:title "RestoreComponentChange"}
      [:type [:= :restore-component]]
      [:id ::sm/uuid]]]

    [:purge-component
     [:map {:title "PurgeComponentChange"}
      [:type [:= :purge-component]]
      [:id ::sm/uuid]]]

    [:add-typography
     [:map {:title "AddTypogrphyChange"}
      [:type [:= :add-typography]]
      [:typography ::ctt/typography]]]

    [:mod-typography
     [:map {:title "ModTypogrphyChange"}
      [:type [:= :mod-typography]]
      [:typography ::ctt/typography]]]

    [:del-typography
     [:map {:title "DelTypogrphyChange"}
      [:type [:= :del-typography]]
      [:id ::sm/uuid]]]

    ]])

(def change?
  (sm/pred-fn ::change))

(def changes?
  (sm/pred-fn [:sequential ::change]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specific helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- without-obj
  "Clear collection from specified obj and without nil values."
  [coll o]
  (into [] (filter #(not= % o)) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page Transformation Changes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Changes Processing Impl

(defn validate-shapes!
  [data-old data-new items]
  (letfn [(validate-shape! [[page-id id]]
            (let [shape-old (dm/get-in data-old [:pages-index page-id :objects id])
                  shape-new (dm/get-in data-new [:pages-index page-id :objects id])]

              ;; If object has changed verify is correct
              (when (and (some? shape-old)
                         (some? shape-new)
                         (not= shape-old shape-new))
                (dm/verify! (cts/shape? shape-new)))))]

    (->> (into #{} (map :page-id) items)
         (mapcat (fn [page-id]
                   (filter #(= page-id (:page-id %)) items)))
         (mapcat (fn [{:keys [type id page-id] :as item}]
                   (sequence
                    (map (partial vector page-id))
                    (case type
                      (:add-obj :mod-obj :del-obj) (cons id nil)
                      (:mov-objects :reg-objects)  (:shapes item)
                      nil))))
         (run! validate-shape!))))

(defmulti process-change (fn [_ change] (:type change)))
(defmulti process-operation (fn [_ _ op] (:type op)))

(defn process-changes
  ([data items]
   (process-changes data items true))

  ([data items verify?]
   ;; When verify? false we spec the schema validation. Currently used to make just
   ;; 1 validation even if the changes are applied twice
   (when verify?
     (dm/verify! (changes? items)))

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
  (let [objects (if page-id
                  (-> data :pages-index (get page-id) :objects)
                  (-> data :components (get component-id) :objects))

        modified-component-ids (atom #{})

        on-touched (fn [shape]
                     ;; When a shape is modified, if it belongs to a main component instance,
                     ;; the component needs to be marked as modified.
                     (let [component-root (ctn/get-component-shape objects shape {:allow-main? true})]
                       (when (ctk/main-instance? component-root)
                         (swap! modified-component-ids conj (:component-id component-root)))))

        update-fn (fn [objects]
                    (if-let [obj (get objects id)]
                      (let [result (reduce (partial process-operation on-touched) obj operations)]
                        (assoc objects id result))
                      objects))

        modify-components (fn [data]
                           (reduce ctkl/set-component-modified
                                   data @modified-component-ids))]

    (as-> data $
      (if page-id
        (d/update-in-when $ [:pages-index page-id :objects] update-fn)
        (d/update-in-when $ [:components component-id :objects] update-fn))
      (modify-components $))))

(defmethod process-change :del-obj
  [data {:keys [page-id component-id id ignore-touched]}]
  (if page-id
    (d/update-in-when data [:pages-index page-id] ctst/delete-shape id ignore-touched)
    (d/update-in-when data [:components component-id] ctst/delete-shape id ignore-touched)))

;; FIXME: remove, seems like this method is already unused
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
                (and (:shape-ref parent)
                     (#{:group :frame} (:type parent))
                     (not ignore-touched))
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
  (ctcl/set-color data color))

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
  (ctyl/update-typography data (:id typography) merge typography))

(defmethod process-change :del-typography
  [data {:keys [id]}]
  (ctyl/delete-typography data id))

;; === Operations

(defmethod process-operation :set
  [on-touched shape op]
  (let [attr            (:attr op)
        group           (get component-sync-attrs attr)
        val             (:val op)
        shape-val       (get shape attr)
        ignore          (:ignore-touched op)
        ignore-geometry (:ignore-geometry op)
        is-geometry?    (and (or (= group :geometry-group)
                                 (and (= group :content-group) (= (:type shape) :path))
                                 (= attr :position-data))
                             (not (#{:width :height} attr))) ;; :content in paths are also considered geometric
                        ;; TODO: the check of :width and :height probably may be removed
                        ;;       after the check added in data/workspace/modifiers/check-delta
                        ;;       function. Better check it and test toroughly when activating
                        ;;       components-v2 mode.
        in-copy?        (ctk/in-component-copy? shape)

        ;; For geometric attributes, there are cases in that the value changes
        ;; slightly (e.g. when rounding to pixel, or when recalculating text
        ;; positions in different zoom levels). To take this into account, we
        ;; ignore geometric changes smaller than 1 pixel.
        equal? (if is-geometry?
                 (gsh/close-attrs? attr val shape-val 1)
                 (gsh/close-attrs? attr val shape-val))]

    (when (and group (not ignore) (not equal?)
               (not (and ignore-geometry is-geometry?)))
      ;; Notify touched even if it's not copy, because it may be a main instance
      (on-touched shape))

    (cond-> shape
      ;; Depending on the origin of the attribute change, we need or not to
      ;; set the "touched" flag for the group the attribute belongs to.
      ;; In some cases we need to ignore touched only if the attribute is
      ;; geometric (position, width or transformation).
      (and in-copy? group (not ignore) (not equal?)
           (not (and ignore-geometry is-geometry?)))
      (-> (update :touched cph/set-touched-group group)
          (dissoc :remote-synced?))

      (nil? val)
      (dissoc attr)

      (some? val)
      (assoc attr val))))

(defmethod process-operation :set-touched
  [_ shape op]
  (let [touched (:touched op)
        in-copy? (ctk/in-component-copy? shape)]
    (if (or (not in-copy?) (nil? touched) (empty? touched))
      (dissoc shape :touched)
      (assoc shape :touched touched))))

(defmethod process-operation :set-remote-synced
  [_ shape op]
  (let [remote-synced? (:remote-synced? op)
        in-copy? (ctk/in-component-copy? shape)]
    (if (or (not in-copy?) (not remote-synced?))
      (dissoc shape :remote-synced?)
      (assoc shape :remote-synced? true))))

(defmethod process-operation :default
  [_ _ op]
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
                                 (cons id (cph/get-parent-ids (:objects page) id)))
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

(defmethod components-changed :mov-objects
  [file-data {:keys [page-id _component-id parent-id shapes] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)

          xform (comp (filter :main-instance?)
                      (map :id))

          check-shape
          (fn [shape-id others]
            (let [all-parents (map (partial ctn/get-shape page)
                                   (concat others (cph/get-parent-ids (:objects page) shape-id)))]
              (into #{} xform all-parents)))]

      (reduce #(set/union %1 (check-shape %2 []))
              (check-shape parent-id [parent-id])
              shapes))))

(defmethod components-changed :del-obj
  [file-data {:keys [id page-id _component-id] :as change}]
  (when page-id
    (let [page (ctpl/get-page file-data page-id)
          shape-and-parents (map (partial ctn/get-shape page)
                                 (cons id (cph/get-parent-ids (:objects page) id)))
          xform (comp (filter :main-instance?)
                      (map :id))]
      (into #{} xform shape-and-parents))))

(defmethod components-changed :default
  [_ _]
  nil)

