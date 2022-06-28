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
   [app.common.types.shape-tree :as ctt]
   [clojure.spec.alpha :as s]))

(s/def ::type #{:page :component})
(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))

(s/def ::container
  (s/keys :req-un [::id ::name ::ctt/objects]
          :opt-un [::type ::path]))

(defn get-shape
  [container shape-id]
  (us/assert ::container container)
  (us/assert ::us/uuid shape-id)
  (-> container
      (get :objects)
      (get shape-id)))

(defn instantiate-component
  [container component component-file position]
  (let [component-shape (get-shape component (:id component))

        orig-pos  (gpt/point (:x component-shape) (:y component-shape))
        delta     (gpt/subtract position orig-pos)

        objects   (:objects container)
        unames    (volatile! (ctt/retrieve-used-names objects))

        frame-id  (ctt/frame-id-by-position objects (gpt/add orig-pos delta))

        update-new-shape
        (fn [new-shape original-shape]
          (let [new-name (ctt/generate-unique-name @unames (:name new-shape))]

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
                     :component-file component-file
                     :component-root? true
                     :name new-name)

              (some? (:parent-id original-shape))
              (dissoc :component-root?))))

        [new-shape new-shapes _]
        (ctt/clone-object component-shape
                          nil
                          (get component :objects)
                          update-new-shape)]

    [new-shape new-shapes]))
 
