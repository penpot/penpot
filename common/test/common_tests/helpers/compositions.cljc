;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.compositions
  (:require
   [common-tests.helpers.files :as thf]))

(defn add-rect
  [file rect-label]
  (thf/add-sample-shape file rect-label
                        :type :rect
                        :name "Rect1"))

(defn add-frame
  [file frame-label]
  (thf/add-sample-shape file frame-label
                        :type :frame
                        :name "Frame1"))

(defn add-frame-with-child
  [file frame-label child-label]
  (-> file
      (add-frame frame-label)
      (thf/add-sample-shape child-label
                            :type :rect
                            :name "Rect1"
                            :parent-label frame-label)))

(defn add-simple-component
  [file component-label root-label child-label]
  (-> file
      (add-frame-with-child root-label child-label)
      (thf/make-component component-label root-label)))

(defn add-simple-component-with-copy
  [file component-label main-root-label main-child-label copy-root-label]
  (-> file
      (add-simple-component component-label main-root-label main-child-label)
      (thf/instantiate-component component-label copy-root-label)))
