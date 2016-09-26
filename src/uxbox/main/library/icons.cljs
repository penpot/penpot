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

(def +collections+
  [{:name "Material design (actions)"
    :type :builtin
    :id 1
    :icons md-actions/+icons+}
   {:name "Material design (alerts)"
    :type :builtin
    :id 2
    :icons md-alerts/+icons+}
   {:name "Material design (Av)"
    :type :builtin
    :id 3
    :icons md-av/+icons+}
   {:name "Material design (Communication)"
    :type :builtin
    :id 4
    :icons md-comm/+icons+}
   {:name "Material design (Content)"
    :type :builtin
    :id 5
    :icons md-content/+icons+}
   {:name "Material design (Device)"
    :type :builtin
    :id 6
    :icons md-device/+icons+}
   {:name "Material design (Editor)"
    :type :builtin
    :id 7
    :icons md-editor/+icons+}
   {:name "Material design (File)"
    :type :builtin
    :id 8
    :icons md-file/+icons+}
   {:name "Material design (Hardware)"
    :type :builtin
    :id 9
    :icons md-hardware/+icons+}
   {:name "Material design (Image)"
    :type :builtin
    :id 10
    :icons md-image/+icons+}
   {:name "Material design (Maps)"
    :type :builtin
    :id 11
    :icons md-maps/+icons+}
   {:name "Material design (Navigation)"
    :type :builtin
    :id 12
    :icons md-nav/+icons+}
   {:name "Material design (Notifications)"
    :type :builtin
    :id 13
    :icons md-not/+icons+}
   {:name "Material design (Social)"
    :type :builtin
    :id 14
    :icons md-social/+icons+}
   {:name "Material design (Toggle)"
    :type :builtin
    :id 15
    :icons md-toggle/+icons+}])
