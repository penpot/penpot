;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.component)

(defn instance-root?
  [shape]
  (some? (:component-id shape)))

(defn instance-of?
  [shape file-id component-id]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) component-id)
       (= (:component-file shape) file-id)))

(defn is-main-of?
  [shape-main shape-inst]
  (and (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-main))
           (= (:shape-ref shape-inst) (:shape-ref shape-main)))))

(defn main-instance?
  "Check if this shape is the root of the main instance of some component."
  [shape]
  (some? (:main-instance? shape)))

(defn is-main-instance?
  "Check if this shape is the root of the main instance of the given component."
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn get-component-root
  [component]
  (if (some? (:main-instance-id component))
    (get-in component [:objects (:main-instance-id component)])
    (get-in component [:objects (:id component)])))

(defn uses-library-components?
  "Check if the shape uses any component in the given library."
  [shape library-id]
  (and (some? (:component-id shape))
       (= (:component-file shape) library-id)))

(defn in-component-instance?
  "Check if the shape is inside a component non-main instance."
  [shape]
  (some? (:shape-ref shape)))

(defn in-component-instance-not-root?
  "Check if the shape is inside a component non-main instance and
  is not the root shape."
  [shape]
  (and (some? (:shape-ref shape))
       (nil? (:component-id shape))))

(defn detach-shape
  "Remove the links and leave it as a plain shape, detached from any component."
  [shape]
  (dissoc shape
          :component-id
          :component-file
          :component-root?
          :remote-synced?
          :shape-ref
          :touched))
