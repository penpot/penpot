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
   [app.common.types.shape-tree :as ctst]
   [clojure.spec.alpha :as s]))

(s/def ::type #{:page :component})
(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))

(s/def ::container
  (s/keys :req-un [::id ::name ::ctst/objects]
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

(defn get-container
  [file type id]
  (us/assert map? file)
  (us/assert ::type type)
  (us/assert uuid? id)

  (-> (if (= type :page)
        (get-in file [:pages-index id])
        (get-in file [:components id]))
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

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects file-id]
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

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))]

    (ctst/clone-object shape nil objects update-new-shape update-original-shape)))

(defn instantiate-component
  [container component component-file-id position]
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

              (some? (:parent-id original-shape))
              (dissoc :component-root?))))

        [new-shape new-shapes _]
        (ctst/clone-object component-shape
                           nil
                           (get component :objects)
                           update-new-shape)]

    [new-shape new-shapes]))
 
