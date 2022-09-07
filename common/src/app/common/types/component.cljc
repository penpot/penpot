;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.component)

(defn instance-root?
  "Check if the shape is the root of an instance or a subinstance."
  [shape]
  (some? (:component-id shape)))

(defn instance-tree-root?
  "Check if the shape is the root of an instance that is no
  subinstance of a higher one."
  [shape]
  (:component-root? shape))

(defn instance-shape?
  "Check if the shape is part of any instance."
  [shape]
  (some? (:shape-ref shape)))
 
(defn instance-of?
  "Check if the shape is the root of a near instance of the component."
  [shape file-id component-id]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) component-id)
       (= (:component-file shape) file-id)))

(defn is-main-of?
  "Check if the first shape is the near main of the second one."
  [shape-main shape-inst]
  (and (not= shape-main shape-inst)
       (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-main))
           (= (:shape-ref shape-inst) (:shape-ref shape-main)))))

(defn is-main-instance?
  "Check if the shape is the root of the main instance of the component."
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn get-component-root
  "Get the root shape of the component."
  [component]
  (get-in component [:objects (:id component)]))

(defn uses-library-components?
  "Check if the shape is the root of an instance of any component in
  the given library."
  [shape library-id]
  (and (some? (:component-id shape))
       (= (:component-file shape) library-id)))

(defn set-touched-group
  "Add a group to the touched flags."
  [touched group]
  (conj (or touched #{}) group))

(defn touched-group?
  "Check if the touched flags contain the given group."
  [shape group]
  ((or (:touched shape) #{}) group))

