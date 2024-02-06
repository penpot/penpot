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
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-container-types
  #{:page :component})

(sm/define! ::container
  [:map
   [:id ::sm/uuid]
   [:type {:optional true}
    [::sm/one-of valid-container-types]]
   [:name :string]
   [:path {:optional true} [:maybe :string]]
   [:modified-at {:optional true} ::sm/inst]
   [:objects {:optional true}
    [:map-of {:gen/max 10} ::sm/uuid :map]]])

(def check-container!
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

  (dm/assert!
   "expected valid container"
   (check-container! container))

  (dm/assert!
   "expected valid uuid for `shape-id`"
   (uuid? shape-id))

  (-> container
      (get :objects)
      (get shape-id)))

(defn shapes-seq
  [container]
  (vals (:objects container)))

(defn update-shape
  [container shape-id f]
  (update-in container [:objects shape-id] f))

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
  "Get the parent top shape linked to a component for this shape, if any"
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

(defn get-copy-root
  "Get the top shape of the copy."
  [objects shape]
  (when (:shape-ref shape)
    (let [parent (cfh/get-parent objects (:id shape))]
      (or (get-copy-root objects parent) shape))))

(defn inside-component-main?
  "Check if the shape is a component main instance or is inside one."
  [objects shape]
  (cond
    (or (nil? shape) (cfh/root? shape))
    false
    (nil? (:parent-id shape))  ; This occurs in the root of components v1
    true
    (ctk/main-instance? shape)
    true
    (ctk/instance-head? shape)
    false
    :else
    (inside-component-main? objects (get objects (:parent-id shape)))))

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
     nil
     (into [new-root] new-children)]))

(defn make-component-shape ;; Only used for components v1
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects file-id components-v2]
  (assert (nil? (:component-id shape)))
  (assert (nil? (:component-file shape)))
  (assert (nil? (:shape-ref shape)))
  (let [frame-ids-map (volatile! {})

        ;; Ensure that the component root is not an instance
        update-new-shape (fn [new-shape original-shape]
                           (when (= (:type original-shape) :frame)
                             (vswap! frame-ids-map assoc (:id original-shape) (:id new-shape)))

                           (cond-> new-shape
                             true
                             (dissoc :component-root)

                             (nil? (:parent-id new-shape))
                             (dissoc :component-id
                                     :component-file
                                     :shape-ref)))

        ;; Make the original shape an instance of the new component.
        ;; If one of the original shape children already was a component
        ;; instance, maintain this instanceness untouched.
        update-original-shape (fn [original-shape new-shape]
                                (cond-> original-shape
                                  (nil? (:shape-ref original-shape))
                                  (-> (assoc :shape-ref (:id new-shape))
                                      (dissoc :touched))

                                  (nil? (:parent-id new-shape))
                                  (assoc :component-id (:id new-shape)
                                         :component-file file-id
                                         :component-root true)

                                  (and (nil? (:parent-id new-shape)) components-v2)
                                  (assoc :main-instance true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root)))

        [new-root-shape new-shapes updated-shapes]
        (ctst/clone-shape shape
                          nil
                          objects
                          :update-new-shape update-new-shape
                          :update-original-shape update-original-shape)

        ;; If frame-id points to a shape inside the component, remap it to the
        ;; corresponding new frame shape. If not, set it to nil.
        remap-frame-id (fn [shape]
                         (update shape :frame-id #(get @frame-ids-map % nil)))]

    [new-root-shape (map remap-frame-id new-shapes) updated-shapes]))

(defn make-component-instance
  "Generate a new instance of the component inside the given container.

  Clone the shapes of the component, generating new names and ids, and
  linking each new shape to the corresponding one of the
  component. Place the new instance coordinates in the given
  position."
  ([container component library-data position components-v2]
   (make-component-instance container component library-data position components-v2 {}))

  ([container component library-data position components-v2
    {:keys [main-instance? force-id force-frame-id keep-ids?]
     :or {main-instance? false force-id nil force-frame-id nil keep-ids? false}}]
   (let [component-page  (when components-v2
                           (ctpl/get-page library-data (:main-instance-page component)))

         component-shape (if components-v2
                           (-> (get-shape component-page (:main-instance-id component))
                               (assoc :parent-id nil) ;; On v2 we force parent-id to nil in order to behave like v1
                               (assoc :frame-id uuid/zero)
                               (d/without-keys ctk/swap-keep-attrs))
                           (get-shape component (:id component)))

         orig-pos        (gpt/point (:x component-shape) (:y component-shape))
         delta           (gpt/subtract position orig-pos)

         objects         (:objects container)
         unames          (volatile! (cfh/get-used-names objects))

         frame-id        (or force-frame-id
                             (ctst/get-frame-id-by-position objects
                                                            (gpt/add orig-pos delta)
                                                            {:skip-components? true
                                                             :bottom-frames? true}))
         frame           (get-shape container frame-id)
         component-frame (get-component-shape (:objects container) frame {:allow-main? true})

         ids-map         (volatile! {})

         update-new-shape
         (fn [new-shape original-shape]
           (let [new-name (:name new-shape)
                 root?    (or (ctk/instance-root? original-shape)   ; If shape is inside a component (not components-v2)
                              (nil? (:parent-id original-shape)))]  ; we detect it by having no parent)

             (when root?
               (vswap! unames conj new-name))

             (vswap! ids-map assoc (:id original-shape) (:id new-shape))

             (cond-> new-shape
               :always
               (-> (gsh/move delta)
                   (dissoc :touched))

               (and main-instance? root?)
               (assoc :main-instance true)

               (not main-instance?)
               (dissoc :main-instance)

               main-instance?
               (dissoc :shape-ref)

               (and (not main-instance?)
                    (or components-v2                        ; In v1, shape-ref points to the remote instance
                        (nil? (:shape-ref original-shape)))) ; in v2, shape-ref points to the near instance
               (assoc :shape-ref (:id original-shape))

               (nil? (:parent-id original-shape))
               (assoc :component-id (:id component)
                      :component-file (:id library-data)
                      :component-root true
                      :name new-name)

               (or (some? (:parent-id original-shape)) ; On v2 we have removed the parent-id for component roots (see above)
                   (some? component-frame))
               (dissoc :component-root))))

         [new-shape new-shapes _]
         (ctst/clone-shape component-shape
                           frame-id
                           (if components-v2 (:objects component-page) (:objects component))
                           :update-new-shape update-new-shape
                           :force-id force-id
                           :keep-ids? keep-ids?
                           :frame-id frame-id
                           :dest-objects (:objects container))

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
  "Check if the shape has any children or parent that is a main component."
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

(defn- invalid-structure-for-component?
  "Check if the structure generated nesting children in parent is invalid in terms of nested components"
  [objects parent children]
  (let [selected-main-instance? (some true? (map #(has-any-main? objects %) children))
        parent-in-component?    (in-any-component? objects parent)
        comps-nesting-loop?     (not (->> children
                                          (map #(cfh/components-nesting-loop? objects (:id %) (:id parent)))
                                          (every? nil?)))]
    (or
      ;;We don't want to change the structure of component copies
     (ctk/in-component-copy? parent)
      ;; If we are moving something containing a main instance the container can't be part of a component (neither main nor copy)
     (and selected-main-instance? parent-in-component?)
      ;; Avoid placing a shape as a direct or indirect child of itself,
      ;; or inside its main component if it's in a copy.
     comps-nesting-loop?)))

(defn find-valid-parent-and-frame-ids
  "Navigate trough the ancestors until find one that is valid"
  [parent-id objects children]
  (let [parent (get objects parent-id)]
    (if (invalid-structure-for-component? objects parent children)
      (find-valid-parent-and-frame-ids (:parent-id parent) objects children)
      [parent-id
       (if (= :frame (:type parent))
         parent-id
         (:frame-id parent))])))
