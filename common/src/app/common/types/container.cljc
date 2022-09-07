;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.container
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.spec :as us]
   [app.common.types.component :as ctk]
   [app.common.types.shape-tree :as ctst]
   [clojure.spec.alpha :as s]))

(s/def ::type #{:page :component})
(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))

(s/def ::container
  ;; (s/keys :req-un [::id ::name ::ctst/objects]
  (s/keys :req-un [::id ::name]
          :opt-un [::type ::path]))

(defn make-container
  [page-or-component type]
  (assoc page-or-component :type type))

(defn page?
  [container]
  (= (:type container) :page))

(defn component?
  [container]
  (= (:type container) :component))

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
  "Get the root shape of an instance, the one that is linked to the component.
  If this is a subinstance, get the most direct root."
  [objects shape]
  (if-not (:shape-ref shape)
    nil
    (if (:component-id shape)
      shape
      (if-let [parent-id (:parent-id shape)]
        (get-component-shape objects (get objects parent-id))
        nil))))

(defn get-root-shape
  "Get the topmost root shape of an instance, the one that is linked to the
  component and without any container instance upwards."
  [objects shape]
  (cond
    (some? (:component-root? shape))
    shape

    (some? (:shape-ref shape))
    (recur objects (get objects (:parent-id shape)))))

(defn get-instances
  "Get all shapes in the objects list that are near instances of the given one

  ---------------------------------------------------------------------------
  TODO: Warning!!! this is a slow operation, since it needs to walk the whole
  objects list. Perhaps there is a way of indexing this someway.
  ---------------------------------------------------------------------------"
  [objects main-shape]
  (filter #(ctk/is-main-of? main-shape %)
          (vals objects)))

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects file-id components-v2]
  (assert (nil? (:component-id shape)))
  (assert (nil? (:component-file shape)))
  (assert (nil? (:shape-ref shape)))
  (let [;; Ensure that the component root is not an instance and
        ;; it's no longer tied to a frame.
        update-new-shape (fn [new-shape _original-shape]
                           (cond-> new-shape
                             true
                             (-> (assoc :frame-id nil)
                                 (dissoc :component-root?))

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
                                  (dissoc :component-root?)))]

    (ctst/clone-object shape nil objects update-new-shape update-original-shape)))

(defn make-component-instance
  "Clone the shapes of the component, generating new names and ids, and linking
  each new shape to the corresponding one of the component. Place the new instance
  coordinates in the given position."
  ([container component component-file-id position]
   (make-component-instance container component component-file-id position {}))

  ([container component component-file-id position
    {:keys [main-instance? force-id] :or {main-instance? false force-id nil}}]
   (let [component-shape (get-shape component (:id component))

         orig-pos  (gpt/point (:x component-shape) (:y component-shape))
         delta     (gpt/subtract position orig-pos)

         objects   (:objects container)
         unames    (volatile! (ctst/retrieve-used-names objects))

         frame-id  (ctst/frame-id-by-position objects (gpt/add orig-pos delta))

         update-new-shape
         (fn [new-shape original-shape]
           (let [new-name (ctst/generate-unique-name @unames (:name new-shape))]

             (when (nil? (:parent-id original-shape))
               (vswap! unames conj new-name))

             (cond-> new-shape
               true
               (as-> $
                 (gsh/move $ delta)
                 (assoc $ :frame-id frame-id)
                 (assoc $ :parent-id
                        (or (:parent-id $) (:frame-id $)))
                 (dissoc $ :touched))

               (nil? (:shape-ref original-shape))
               (assoc :shape-ref (:id original-shape))

               (nil? (:parent-id original-shape))
               (assoc :component-id (:id original-shape)
                      :component-file component-file-id
                      :component-root? true
                      :name new-name)

               (and (nil? (:parent-id original-shape)) main-instance?)
               (assoc :main-instance? true)

               (some? (:parent-id original-shape))
               (dissoc :component-root?))))

         [new-shape new-shapes _]
         (ctst/clone-object component-shape
                            nil
                            (get component :objects)
                            update-new-shape
                            (fn [object _] object)
                            force-id)]

     [new-shape new-shapes])))

