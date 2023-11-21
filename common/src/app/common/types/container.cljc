;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.container
  (:require
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

(sm/def! ::container
  [:map
   [:id ::sm/uuid]
   [:type {:optional true}
    [::sm/one-of valid-container-types]]
   [:name :string]
   [:path {:optional true} [:maybe :string]]
   [:modified-at {:optional true} ::sm/inst]
   [:objects {:optional true}
    [:map-of {:gen/max 10} ::sm/uuid :map]]])

(def container?
  (sm/pred-fn ::container))

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
   (container? container))

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
   (cond
     (nil? shape)
     nil

     (cfh/root? shape)
     nil

     (ctk/instance-root? shape)
     shape

     (and (not (ctk/in-component-copy? shape)) (not allow-main?))
     nil

     :else
     (get-component-shape objects (get objects (:parent-id shape)) options))))

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
  (let [new-id       (uuid/next)
        new-root     (assoc root
                            :component-id new-id
                            :component-file file-id
                            :component-root true
                            :main-instance true)
        new-children (->> (cfh/get-children objects (:id root))
                          (map #(dissoc % :component-root)))]
    [(assoc new-root :id new-id)
     nil
     (into [new-root] new-children)]))

(defn make-component-shape
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
        (ctst/clone-object shape nil objects update-new-shape update-original-shape)

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
                               (assoc :parent-id nil)
                               (assoc :frame-id uuid/zero))
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

               (some? (:parent-id original-shape))
               (dissoc :component-root))))

         [new-shape new-shapes _]
         (ctst/clone-object component-shape
                            frame-id
                            (if components-v2 (:objects component-page) (:objects component))
                            update-new-shape
                            (fn [object _] object)
                            force-id
                            keep-ids?)

         ;; If frame-id points to a shape inside the component, remap it to the
         ;; corresponding new frame shape. If not, set it to the destination frame.
         ;; Also fix empty parent-id.
         remap-ids
         (fn [shape]
           (as-> shape $
             (update $ :frame-id #(get @ids-map % frame-id))
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
