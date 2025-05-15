;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.container
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.schema :as sm]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as ctt]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-container-types
  #{:page :component})

(sm/register!
 ^{::sm/type ::container}
 [:map
  [:id ::sm/uuid]
  [:type {:optional true}
   [::sm/one-of valid-container-types]]
  [:name :string]
  [:path {:optional true} [:maybe :string]]
  [:modified-at {:optional true} ::sm/inst]
  [:objects {:optional true}
   [:map-of {:gen/max 10} ::sm/uuid :map]]
  [:plugin-data {:optional true} ::ctpg/plugin-data]])

(def check-container
  (sm/check-fn ::container))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-container
  [page-or-component type]
  (assoc page-or-component :type type))

(defn page?
  [container]
  (= (:type container) :page))

(defn component?
  [container]
  (= (:type container) :component))

(defn get-container
  [file type id]
  (dm/assert! (map? file))
  (dm/assert! (contains? valid-container-types type))
  (dm/assert! (uuid? id))

  (-> (if (= type :page)
        (ctpl/get-page file id)
        (ctkl/get-component file id))
      (assoc :type type)))

(defn get-shape
  [container shape-id]

  (assert (check-container container))
  (assert (uuid? shape-id)
          "expected valid uuid for `shape-id`")

  (-> container
      (get :objects)
      (get shape-id)))

(defn shapes-seq
  [container]
  (vals (:objects container)))

(defn update-shape
  [container shape-id f]
  (update-in container [:objects shape-id] f))

(defn get-container-root
  [container]
  (d/seek #(or (nil? (:parent-id %)) (= (:parent-id %) uuid/zero)) (shapes-seq container)))

(defn get-direct-children
  [container shape]
  (map #(get-shape container %) (:shapes shape)))

(defn get-children-in-instance
  "Get the shape and their children recursively, but stopping when
   a component nested instance is found."
  [objects id]
  (letfn [(get-children-rec [children id]
            (let [shape (get objects id)]
              (if (and (ctk/instance-head? shape) (seq children))
                children
                (into (conj children shape)
                      (mapcat #(get-children-rec children %) (:shapes shape))))))]
    (get-children-rec [] id)))

(defn get-component-shape
  "Get the parent top shape linked to a component main for this shape, if any"
  ([objects shape] (get-component-shape objects shape nil))
  ([objects shape {:keys [allow-main?] :or {allow-main? false} :as options}]
   (let [parent (get objects (:parent-id shape))]
     (cond
       (nil? shape)
       nil

       (cfh/root? shape)
       nil

       (ctk/instance-root? shape)
       shape

       (and (not (ctk/in-component-copy? shape)) (not allow-main?))
       nil

       (and (ctk/instance-head? shape) (not (ctk/in-component-copy? parent)))
       shape ; This case is a copy root inside a main component

       :else
       (get-component-shape objects parent options)))))

(defn get-head-shape
  "Get the parent top or nested shape linked to a component for this shape, if any"
  ([objects shape] (get-head-shape objects shape nil))
  ([objects shape {:keys [allow-main?] :or {allow-main? false} :as options}]
   (cond
     (nil? shape)
     nil

     (cfh/root? shape)
     nil

     (ctk/instance-head? shape)
     shape

     (and (not (ctk/in-component-copy? shape)) (not allow-main?))
     nil

     :else
     (get-head-shape objects (get objects (:parent-id shape)) options))))

(defn get-child-heads
  "Get all recursive childs that are heads (when a head is found, do not
   continue down looking for subsequent nested heads)."
  [objects shape-id]
  (let [shape (get objects shape-id)]
    (if (nil? shape)
      []
      (if (ctk/instance-head? shape)
        [shape]
        (mapcat #(get-child-heads objects %) (:shapes shape))))))

(defn get-parent-heads
  "Get all component heads that are ancestors of the shape, in top-down order
   (include self if it's also a head)."
  [objects shape]
  (->> (cfh/get-parents-with-self objects (:id shape))
       (filter ctk/instance-head?)
       (reverse)))

(defn get-parent-copy-heads
  "Get all component heads that are ancestors of the shape, in top-down order,
   excluding mains (include self if it's also a head)."
  [objects shape]
  (->> (cfh/get-parents-with-self objects (:id shape))
       (filter #(and (ctk/instance-head? %) (ctk/in-component-copy? %)))
       (reverse)))

(defn get-nesting-level-delta
  "Get how many levels a shape will 'go up' if moved under the new parent."
  [objects shape new-parent]
  (let [orig-heads (->> (get-parent-copy-heads objects shape)
                        (remove #(= (:id %) (:id shape))))
        dest-heads (get-parent-copy-heads objects new-parent)

        ;; Calculate how many parent heads share in common the original
        ;; shape and the new parent.
        pairs        (map vector orig-heads dest-heads)
        common-count (count (take-while (fn [a b] (= a b)) pairs))]

    (- (count orig-heads) common-count)))

(defn get-instance-root
  "Get the parent shape at the top of the component instance (main or copy)."
  [objects shape]
  (cond
    (nil? shape)
    nil

    (cfh/root? shape)
    nil

    (ctk/instance-root? shape)
    shape

    :else
    (get-instance-root objects (get objects (:parent-id shape)))))

(defn find-component-main
  "If the shape is a component main instance or is inside one, return that instance"
  ([objects shape]
   (find-component-main objects shape true))
  ([objects shape only-direct-child?]
   (cond
     (or (nil? shape) (cfh/root? shape))
     nil
     (nil? (:parent-id shape))  ; This occurs in the root of components v1
     shape
     (ctk/main-instance? shape)
     shape
     (and only-direct-child?           ;; If we are asking only for direct childs of a component-main,
          (ctk/instance-head? shape))  ;; stop when we found a instance-head that isn't main-instance
     nil
     (and (not only-direct-child?)
          (ctk/instance-root? shape))
     nil
     :else
     (find-component-main objects (get objects (:parent-id shape))))))

(defn inside-component-main?
  "Check if the shape is a component main instance or is inside one."
  ([objects shape]
   (inside-component-main? objects shape true))
  ([objects shape only-direct-child?]
   (some? (find-component-main objects shape only-direct-child?))))

(defn in-any-component?
  "Check if the shape is part of any component (main or copy), wether it's
   head or not."
  [objects shape]
  (or (ctk/in-component-copy? shape)
      (ctk/instance-head? shape)
      (inside-component-main? objects shape)))

(defn convert-shape-in-component
  "Set the shape as a main root instance, pointing to a new component.
   Also remove component-root of all children. Return the same structure
   as make-component-shape."
  [root objects file-id]
  (let [new-id            (uuid/next)
        inside-component? (some? (get-instance-root objects root))
        new-root          (cond-> (assoc root
                                         :component-id new-id
                                         :component-file file-id
                                         :main-instance true)
                            (not inside-component?)
                            (assoc :component-root true))
        new-children       (->> (cfh/get-children objects (:id root))
                                (map #(dissoc % :component-root)))]
    [(assoc new-root :id new-id)
     (into [new-root] new-children)]))

(defn remove-swap-keep-attrs
  "Remove flex children properties except the fit-content for flex layouts. These are properties
  that we don't have to propagate to copies but will be respected when swapping components"
  [shape]
  (let [layout-item-h-sizing (when (and (ctl/flex-layout? shape) (ctl/auto-width? shape)) :auto)
        layout-item-v-sizing (when (and (ctl/flex-layout? shape) (ctl/auto-height? shape)) :auto)]
    (-> shape
        (d/without-keys ctk/swap-keep-attrs)
        (cond-> (some? layout-item-h-sizing)
          (assoc :layout-item-h-sizing layout-item-h-sizing))
        (cond-> (some? layout-item-v-sizing)
          (assoc :layout-item-v-sizing layout-item-v-sizing)))))

(defn make-component-instance
  "Generate a new instance of the component inside the given container.

  Clone the shapes of the component, generating new names and ids, and
  linking each new shape to the corresponding one of the
  component. Place the new instance coordinates in the given
  position.

  WARNING: This process does not remap media references (on fills, strokes, ...); that is
  delegated to an async process on the backend side that checks unreferenced shapes and
  automatically creates correct references."
  ([page component library-data position]
   (make-component-instance page component library-data position {}))
  ([page component library-data position
    {:keys [main-instance? force-id force-frame-id keep-ids?]
     :or {main-instance? false force-id nil force-frame-id nil keep-ids? false}}]
   (let [component-page  (ctpl/get-page library-data (:main-instance-page component))

         component-shape (-> (get-shape component-page (:main-instance-id component))
                             (assoc :parent-id nil) ;; On v2 we force parent-id to nil in order to behave like v1
                             (assoc :frame-id uuid/zero)
                             (remove-swap-keep-attrs))


         orig-pos        (gpt/point (:x component-shape) (:y component-shape))
         delta           (gpt/subtract position orig-pos)

         objects         (:objects page)
         unames          (volatile! (cfh/get-used-names objects))

         component-children
         (d/index-by :id (cfh/get-children-with-self objects (:id component-shape)))

         frame-id        (or force-frame-id
                             (ctst/get-frame-id-by-position objects
                                                            (gpt/add orig-pos delta)
                                                            {:skip-components? true
                                                             :bottom-frames? true
                                                             ;; We must avoid that destiny frame is inside the component frame
                                                             :validator #(and
                                                                          ;; We must avoid that destiny frame is inside the component frame
                                                                          (nil? (get component-children (:id %)))
                                                                          ;; We must avoid that destiny frame is inside a copy
                                                                          (not (ctk/in-component-copy? %)))}))
         frame           (get-shape page frame-id)
         component-frame (get-component-shape objects frame {:allow-main? true})

         ids-map         (volatile! {})

         update-new-shape
         (fn [new-shape original-shape]
           (let [new-name (:name new-shape)
                 root?    (ctk/instance-root? original-shape)]

             (when root?
               (vswap! unames conj new-name))

             (vswap! ids-map assoc (:id original-shape) (:id new-shape))

             (cond-> new-shape
               :always
               (-> (gsh/move delta)
                   (dissoc :touched :variant-id :variant-name))

               (and main-instance? root?)
               (assoc :main-instance true)

               (not main-instance?)
               (dissoc :main-instance)

               main-instance?
               (dissoc :shape-ref)

               (not main-instance?)
               (assoc :shape-ref (:id original-shape)) ; shape-ref points to the near instance

               (nil? (:parent-id original-shape))
               (assoc :component-id (:id component)
                      :component-file (:id library-data)
                      :component-root true
                      :name new-name)

               (or (some? (:parent-id original-shape)) ; On v2 we have removed the parent-id for component roots
                   (some? component-frame))
               (dissoc :component-root))))

         [new-shape new-shapes _]
         (ctst/clone-shape component-shape
                           frame-id
                           (:objects component-page)
                           :update-new-shape update-new-shape
                           :force-id force-id
                           :keep-ids? keep-ids?
                           :frame-id frame-id
                           :dest-objects (:objects page))

         ;; Fix empty parent-id and remap all grid cells to the new ids.
         remap-ids
         (fn [shape]
           (as-> shape $
             (update $ :parent-id #(or % (:frame-id $)))
             (cond-> $
               (ctl/grid-layout? shape)
               (ctl/remap-grid-cells @ids-map))))]

     [(remap-ids new-shape)
      (map remap-ids new-shapes)])))

(defn get-first-not-copy-parent
  "Go trough the parents until we find a shape that is not a copy of a component."
  [objects id]
  (let [shape (get objects id)]
    (if (ctk/in-component-copy? shape)
      (get-first-not-copy-parent objects (:parent-id shape))
      shape)))

(defn has-any-copy-parent?
  "Check if the shape has any parent that is a copy of a component."
  [objects shape]
  (let [parent (get objects (:parent-id shape))]
    (if (nil? parent)
      false
      (if (ctk/in-component-copy? parent)
        true
        (has-any-copy-parent? objects (:parent-id shape))))))

(defn has-any-main?
  "Check if the shape is a main component or has any children or parent that is a main component."
  [objects shape]
  (let [children (cfh/get-children-with-self objects (:id shape))
        parents  (cfh/get-parents objects (:id shape))]
    (or
     (some ctk/main-instance? children)
     (some ctk/main-instance? parents))))

(defn valid-shape-for-component?
  "Check if a main component can be generated from this shape in terms of nested components:
  - A main can't be the ancestor of another main
  - A main can't be nested in copies"
  [objects shape]
  (and
   (not (has-any-main? objects shape))
   (not (has-any-copy-parent? objects shape))))


(defn collect-main-shapes [shape objects]
  (if (ctk/main-instance? shape)
    [shape]
    (if-let [children (cfh/get-children objects (:id shape))]
      (mapcat collect-main-shapes children objects)
      [])))

(defn- invalid-structure-for-component?
  "Check if the structure generated nesting children in parent is invalid in terms of nested components"
  [objects parent children pasting? libraries]
  (let [; If the original shapes had been cutted, and we are pasting them now, they aren't
        ; in objects. We can add them to locate later
        objects (merge objects
                       (into {} (map (juxt :id identity) children)))

        ; When we are pasting, the main shapes will be pasted as copies, unless the
        ; original component doesn't exist or is deleted. So for this function purposes, they
        ; are removed from the list
        remove? (fn [shape]
                  (let [component (get-in libraries [(:component-file shape) :data :components (:component-id shape)])]
                    (and component (not (:deleted component)))))

        selected-components (cond->> (mapcat collect-main-shapes children objects)
                              pasting?
                              (remove #(remove? %)))

        selected-main-instance? (seq selected-components)
        parent-in-component?    (in-any-component? objects parent)
        comps-nesting-loop?     (not (->> children
                                          (map #(cfh/components-nesting-loop? objects (:id %) (:id parent)))
                                          (every? nil?)))]
    (or
      ;;We don't want to change the structure of component copies
     (ctk/in-component-copy? parent)
     (has-any-copy-parent? objects parent)
      ;; If we are moving something containing a main instance the container can't be part of a component (neither main nor copy)
     (and selected-main-instance? parent-in-component?)
      ;; Avoid placing a shape as a direct or indirect child of itself,
      ;; or inside its main component if it's in a copy.
     comps-nesting-loop?)))

(defn find-valid-parent-and-frame-ids
  "Navigate trough the ancestors until find one that is valid. Returns [ parent-id frame-id ]"
  ([parent-id objects children]
   (find-valid-parent-and-frame-ids parent-id objects children false nil))
  ([parent-id objects children pasting? libraries]
   (letfn [(get-frame [parent-id]
             (if (cfh/frame-shape? objects parent-id) parent-id (get-in objects [parent-id :frame-id])))]
     (let [parent (get objects parent-id)
           ;; We can always move the children to the parent they already have.
           ;; But if we are pasting, those are new items, so it is considered a change
           no-changes?
           (and (every? #(= parent-id (:parent-id %)) children)
                (not pasting?))

           ;; When pasting frames, children have the frames and their children
           ;; We need to check only the top shapes
           children-ids (set (map :id children))
           top-children (remove #(contains? children-ids (:parent-id %)) children)

           ;; Are all the top-children a main-instance of a component?
           all-main?
           (every? ctk/main-instance? top-children)

           any-main-descendant
           (some
            (fn [shape]
              (some ctk/main-instance? (cfh/get-children-with-self objects (:id shape))))
            children)

           ;; Are all the top-children a main-instance of a cutted component?
           all-comp-cut?
           (when all-main?
             (->> top-children
                  (map #(ctkl/get-component (dm/get-in libraries [(:component-file %) :data])
                                            (:component-id %)
                                            true))
                  (every? :deleted)))]
       (if (or no-changes?
               (and (not (invalid-structure-for-component? objects parent children pasting? libraries))
                    ;; If we are moving into a main component, no descendant can be main
                    (or (nil? any-main-descendant) (not (ctk/main-instance? parent)))
                    ;; If we are moving into a variant-container, all the items should be main
                    ;; so if we are pasting, only allow main instances that are cut-and-pasted
                    (or (not (ctk/is-variant-container? parent))
                        (and (not pasting?) all-main?)
                        all-comp-cut?)))
         [parent-id (get-frame parent-id)]
         (recur (:parent-id parent) objects children pasting? libraries))))))

;; --- SHAPE UPDATE

(defn- get-token-groups
  [shape new-applied-tokens]
  (let [old-applied-tokens  (d/nilv (:applied-tokens shape) #{})
        changed-token-attrs (filter #(not= (get old-applied-tokens %) (get new-applied-tokens %))
                                    ctt/all-keys)
        changed-groups      (into #{}
                                  (comp (map ctt/token-attr->shape-attr)
                                        (map #(get ctk/sync-attrs %))
                                        (filter some?))
                                  changed-token-attrs)]
    changed-groups))

(defn set-shape-attr
  "Assign attribute to shape with touched logic.

  The returned shape will contain a metadata associated with it
  indicating if shape is touched or not."
  [shape attr val & {:keys [ignore-touched ignore-geometry]}]
  (let [group        (get ctk/sync-attrs attr)
        token-groups (when (= attr :applied-tokens)
                       (get-token-groups shape val))
        shape-val    (get shape attr)

        ignore?
        (or ignore-touched
            ;; position-data is a derived attribute
            (= attr :position-data))

        is-geometry?
        (and (or (= group :geometry-group)   ;; never triggers touched by itself
                 (and (= group :content-group)
                      (= (:type shape) :path)))
             ;; :content in paths are also considered geometric
             (not (#{:width :height} attr)))

        ;; TODO: the check of :width and :height probably may be
        ;; removed after the check added in
        ;; data/workspace/modifiers/check-delta function.
        in-copy?
        (ctk/in-component-copy? shape)

        ;; For geometric attributes, there are cases in that the value changes
        ;; slightly (e.g. when rounding to pixel, or when recalculating text
        ;; positions in different zoom levels). To take this into account, we
        ;; ignore geometric changes smaller than 1 pixel.
        equal?
        (if is-geometry?
          (gsh/close-attrs? attr val shape-val 1)
          (gsh/close-attrs? attr val shape-val))

        touched?
        (and group (not equal?) (not (and ignore-geometry is-geometry?)))]

    (cond-> shape
      ;; Depending on the origin of the attribute change, we need or not to
      ;; set the "touched" flag for the group the attribute belongs to.
      ;; In some cases we need to ignore touched only if the attribute is
      ;; geometric (position, width or transformation).
      (and in-copy?
           (or (and group (not equal?)) (seq token-groups))
           (not ignore?) (not (and ignore-geometry is-geometry?)))
      (-> (update :touched (fn [touched]
                             (reduce #(ctk/set-touched-group %1 %2)
                                     touched
                                     (if group
                                       (cons group token-groups)
                                       token-groups))))
          (dissoc :remote-synced))

      (nil? val)
      (dissoc attr)

      (some? val)
      (assoc attr val)

      :always
      (vary-meta assoc ::touched touched?))))
