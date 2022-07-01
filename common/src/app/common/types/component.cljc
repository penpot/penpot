;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.component)
 
(defn instance-of?
  [shape file-id component]
  (and (some? (:component-id shape))
       (some? (:component-file shape))
       (= (:component-id shape) (:id component))
       (= (:component-file shape) file-id)))

(defn is-main-of?
  [shape-main shape-inst]
  (and (:shape-ref shape-inst)
       (or (= (:shape-ref shape-inst) (:id shape-main))
           (= (:shape-ref shape-inst) (:shape-ref shape-main)))))

(defn is-main-instance?
  [shape-id page-id component]
  (and (= shape-id (:main-instance-id component))
       (= page-id (:main-instance-page component))))

(defn get-component-root
  [component]
  (get-in component [:objects (:id component)]))

