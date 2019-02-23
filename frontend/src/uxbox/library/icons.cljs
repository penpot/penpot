;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.library.icons
  (:require [uxbox.library.icons.material.actions :as md-actions]
            [uxbox.library.icons.material.alerts :as md-alerts]
            [uxbox.library.icons.material.av :as md-av]
            [uxbox.library.icons.material.communication :as md-comm]
            [uxbox.library.icons.material.content :as md-content]
            [uxbox.library.icons.material.device :as md-device]
            [uxbox.library.icons.material.editor :as md-editor]
            [uxbox.library.icons.material.file :as md-file]
            [uxbox.library.icons.material.hardware :as md-hardware]
            [uxbox.library.icons.material.image :as md-image]
            [uxbox.library.icons.material.maps :as md-maps]
            [uxbox.library.icons.material.navigation :as md-nav]
            [uxbox.library.icons.material.notification :as md-not]
            [uxbox.library.icons.material.social :as md-social]
            [uxbox.library.icons.material.toggle :as md-toggle]
            [uxbox.util.data :refer (index-by)]))

(def collections-list
  [{:name "Material design (actions)"
    :id #uuid "00000000-0000-0000-0000-100000000001"
    :type :builtin
    :created-at 1
    :icons md-actions/+icons+}
   {:name "Material design (alerts)"
    :id #uuid "00000000-0000-0000-0000-100000000002"
    :type :builtin
    :created-at 2
    :icons md-alerts/+icons+}
   {:name "Material design (Av)"
    :id #uuid "00000000-0000-0000-0000-100000000003"
    :type :builtin
    :created-at 3
    :icons md-av/+icons+}
   {:name "Material design (Communication)"
    :id #uuid "00000000-0000-0000-0000-100000000004"
    :type :builtin
    :created-at 4
    :icons md-comm/+icons+}
   {:name "Material design (Content)"
    :id #uuid "00000000-0000-0000-0000-100000000005"
    :type :builtin
    :created-at 5
    :icons md-content/+icons+}
   {:name "Material design (Device)"
    :id #uuid "00000000-0000-0000-0000-100000000006"
    :type :builtin
    :created-at 6
    :icons md-device/+icons+}
   {:name "Material design (Editor)"
    :id #uuid "00000000-0000-0000-0000-100000000007"
    :type :builtin
    :created-at 7
    :icons md-editor/+icons+}
   {:name "Material design (File)"
    :id #uuid "00000000-0000-0000-0000-100000000008"
    :type :builtin
    :created-at 8
    :icons md-file/+icons+}
   {:name "Material design (Hardware)"
    :id #uuid "00000000-0000-0000-0000-100000000009"
    :type :builtin
    :created-at 9
    :icons md-hardware/+icons+}
   {:name "Material design (Image)"
    :id #uuid "00000000-0000-0000-0000-1000000000010"
    :type :builtin
    :created-at 10
    :icons md-image/+icons+}
   {:name "Material design (Maps)"
    :id #uuid "00000000-0000-0000-0000-100000000011"
    :type :builtin
    :created-at 11
    :icons md-maps/+icons+}
   {:name "Material design (Navigation)"
    :id #uuid "00000000-0000-0000-0000-100000000012"
    :type :builtin
    :created-at 12
    :icons md-nav/+icons+}
   {:name "Material design (Notifications)"
    :id #uuid "00000000-0000-0000-0000-100000000013"
    :type :builtin
    :created-at 13
    :icons md-not/+icons+}
   {:name "Material design (Social)"
    :id #uuid "00000000-0000-0000-0000-100000000014"
    :type :builtin
    :created-at 14
    :icons md-social/+icons+}
   {:name "Material design (Toggle)"
    :id #uuid "00000000-0000-0000-0000-100000000015"
    :type :builtin
    :created-at 15
    :icons md-toggle/+icons+}])

(def collections
  (index-by collections-list :id))
