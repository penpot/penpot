;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.library.icons
  (:require [uxbox.main.library.icons.material.actions :as md-actions]
            [uxbox.main.library.icons.material.alerts :as md-alerts]
            [uxbox.main.library.icons.material.av :as md-av]
            [uxbox.main.library.icons.material.communication :as md-comm]
            [uxbox.main.library.icons.material.content :as md-content]
            [uxbox.main.library.icons.material.device :as md-device]
            [uxbox.main.library.icons.material.editor :as md-editor]
            [uxbox.main.library.icons.material.file :as md-file]
            [uxbox.main.library.icons.material.hardware :as md-hardware]
            [uxbox.main.library.icons.material.image :as md-image]
            [uxbox.main.library.icons.material.maps :as md-maps]
            [uxbox.main.library.icons.material.navigation :as md-nav]
            [uxbox.main.library.icons.material.notification :as md-not]
            [uxbox.main.library.icons.material.social :as md-social]
            [uxbox.main.library.icons.material.toggle :as md-toggle]
            ))

(def +icons+
  (vec (concat md-actions/+icons+
               md-alerts/+icons+
               md-av/+icons+
               md-comm/+icons+
               md-content/+icons+
               md-device/+icons+
               md-editor/+icons+
               md-file/+icons+
               md-hardware/+icons+
               md-image/+icons+
               md-maps/+icons+
               md-nav/+icons+
               md-not/+icons+
               md-social/+icons+
               md-toggle/+icons+)))

(def +collections+
  [{:name "Material design (actions)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728400"}
   {:name "Material design (alerts)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728401"}
   {:name "Material design (Av)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728402"}
   {:name "Material design (Communication)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728403"}
   {:name "Material design (Content)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728404"}
   {:name "Material design (Device)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728405"}
   {:name "Material design (Editor)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728406"}
   {:name "Material design (File)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728407"}
   {:name "Material design (Hardware)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728408"}
   {:name "Material design (Image)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728409"}
   {:name "Material design (Maps)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728410"}
   {:name "Material design (Navigation)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728411"}
   {:name "Material design (Notifications)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728412"}
   {:name "Material design (Social)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728413"}
   {:name "Material design (Toggle)"
    :type :builtin
    :id #uuid "3efa8416-d9d7-4d3d-b60d-a456b2728414"}])
