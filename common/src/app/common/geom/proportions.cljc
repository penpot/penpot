;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.proportions
  (:require
   [app.common.data :as d]))

;; --- Proportions

(defn assign-proportions
  [shape]
  (let [{:keys [width height]} (:selrect shape)]
    (assoc shape :proportion (float (/ width height))))) ; Note: we need to convert explicitly to float.
                                                         ; In Clojure (not clojurescript) when we divide
;; --- Setup Proportions                                 ; two integers it does not create a float, but
                                                         ; a clojure.lang.Ratio object.
(defn setup-proportions-image
  [{:keys [metadata] :as shape}]
  (let [{:keys [width height]} metadata]
    (assoc shape
           :proportion (float (/ width height))
           :proportion-lock true)))

(defn setup-proportions-size
  [{{:keys [width height]} :selrect :as shape}]
  (assoc shape
         :proportion (float (/ width height))
         :proportion-lock true))

(defn setup-proportions-const
  [shape]
  (assoc shape
         :proportion 1.0
         :proportion-lock false))

(defn setup-proportions
  [{:keys [type] :as shape}]
  (let [image-fill? (and (d/not-empty? (:fills shape))
                         (every? #(some? (:fill-image %)) (:fills shape)))]
    (cond
      (= type :svg-raw) (setup-proportions-size shape)
      (= type :image)   (setup-proportions-image shape)
      (= type :text)    shape
      image-fill?       (setup-proportions-size shape)
      :else             (setup-proportions-const shape))))
