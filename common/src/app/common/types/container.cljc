;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.container
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.common :as common]
   [app.common.spec :as us]
   [app.common.types.components-list :as ctkl]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [clojure.spec.alpha :as s]))

(s/def ::type #{:page :component})
(s/def ::id uuid?)
(s/def ::name ::us/string)
(s/def ::path (s/nilable ::us/string))

(s/def ::container
  (s/keys :req-un [::id ::name]
          :opt-un [::type ::path ::ctst/objects]))

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
  (us/assert map? file)
  (us/assert ::type type)
  (us/assert uuid? id)

  (-> (if (= type :page)
        (ctpl/get-page file id)
        (ctkl/get-component file id))
      (assoc :type type)))

(defn get-shape
  [container shape-id]
  (us/assert ::container container)
  (us/assert ::us/uuid shape-id)
  (-> container
      (get :objects)
      (get shape-id)))

(defn shapes-seq
  [container]
  (vals (:objects container)))

(defn update-shape
  [container shape-id f]
  (update-in container [:objects shape-id] f))

(defn get-component-shape
  "Get the parent shape linked to a component for this shape, if any"
  [objects shape]
  (if-not (:shape-ref shape)
    nil
    (if (:component-id shape)
      shape
      (if-let [parent-id (:parent-id shape)]
        (get-component-shape objects (get objects parent-id))
        nil))))

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
                             (dissoc :component-root?)

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
                                         :component-root? true)

                                  (and (nil? (:parent-id new-shape)) components-v2)
                                  (assoc :main-instance? true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))

        [new-root-shape new-shapes updated-shapes]
        (ctst/clone-object shape nil objects update-new-shape update-original-shape)

        ;; If frame-id points to a shape inside the component, remap it to the
        ;; corresponding new frame shape. If not, set it to nil.
        remap-frame-id (fn [shape]
                         (update shape :frame-id #(get @frame-ids-map % nil)))]

    [new-root-shape (map remap-frame-id new-shapes) updated-shapes]))

(defn make-component-instance
  "Generate a new instance of the component inside the given container.

  Clone the shapes of the component, generating new names and ids, and linking
  each new shape to the corresponding one of the component. Place the new instance
  coordinates in the given position."
  ([container component library-data position components-v2]
   (make-component-instance container component library-data position components-v2 {}))

  ([container component library-data position components-v2
    {:keys [main-instance? force-id] :or {main-instance? false force-id nil}}]
   (let [component-page  (when components-v2
                           (ctpl/get-page library-data (:main-instance-page component)))
         component-shape (if components-v2
                           (-> (get-shape component-page (:main-instance-id component))
                               (assoc :parent-id nil))
                           (get-shape component (:id component)))

         orig-pos        (gpt/point (:x component-shape) (:y component-shape))
         delta           (gpt/subtract position orig-pos)

         objects         (:objects container)
         unames          (volatile! (common/retrieve-used-names objects))

         frame-id        (ctst/frame-id-by-position objects (gpt/add orig-pos delta))
         frame-ids-map   (volatile! {})

         update-new-shape
         (fn [new-shape original-shape]
           (let [new-name (:name new-shape)]

             (when (nil? (:parent-id original-shape))
               (vswap! unames conj new-name))

             (when (= (:type original-shape) :frame)
               (vswap! frame-ids-map assoc (:id original-shape) (:id new-shape)))

             (cond-> new-shape
               :always
               (-> (gsh/move delta)
                   (dissoc :touched :main-instance?))

               (nil? (:shape-ref original-shape))
               (assoc :shape-ref (:id original-shape))

               (nil? (:parent-id original-shape))
               (assoc :component-id (:id component)
                      :component-file (:id library-data)
                      :component-root? true
                      :name new-name)

               (and (nil? (:parent-id original-shape)) main-instance? components-v2)
               (assoc :main-instance? true)

               (some? (:parent-id original-shape))
               (dissoc :component-root?))))

         [new-shape new-shapes _]
         (ctst/clone-object component-shape
                            nil
                            (if components-v2 (:objects component-page) (:objects component))
                            update-new-shape
                            (fn [object _] object)
                            force-id)

        ;; If frame-id points to a shape inside the component, remap it to the
        ;; corresponding new frame shape. If not, set it to the destination frame.
        ;; Also fix empty parent-id.
         remap-frame-id (fn [shape]
                          (as-> shape $
                            (update $ :frame-id #(get @frame-ids-map % frame-id))
                            (update $ :parent-id #(or % (:frame-id $)))))]

     [new-shape (map remap-frame-id new-shapes)])))

