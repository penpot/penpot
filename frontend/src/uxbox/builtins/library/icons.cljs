;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.builtins.library.icons
  (:require [uxbox.builtins.library.icons.material.actions :as md-actions]
            [uxbox.builtins.library.icons.material.alerts :as md-alerts]
            [uxbox.builtins.library.icons.material.av :as md-av]
            [uxbox.builtins.library.icons.material.communication :as md-comm]
            [uxbox.builtins.library.icons.material.content :as md-content]
            [uxbox.builtins.library.icons.material.device :as md-device]
            [uxbox.builtins.library.icons.material.editor :as md-editor]
            [uxbox.builtins.library.icons.material.file :as md-file]
            [uxbox.builtins.library.icons.material.hardware :as md-hardware]
            [uxbox.builtins.library.icons.material.image :as md-image]
            [uxbox.builtins.library.icons.material.maps :as md-maps]
            [uxbox.builtins.library.icons.material.navigation :as md-nav]
            [uxbox.builtins.library.icons.material.notification :as md-not]
            [uxbox.builtins.library.icons.material.social :as md-social]
            [uxbox.builtins.library.icons.material.toggle :as md-toggle]
            [uxbox.util.data :refer (index-by)]
            [uxbox.util.uuid :as uuid]))

(def collections-list
  [{:name "Material design (Actions)"
    :id md-actions/+collection-icons-id+
    :type :builtin
    :created-at 1
    :icons md-actions/+icons+}
   {:name "Material design (Alerts)"
    :id md-alerts/+collection-icons-id+
    :type :builtin
    :created-at 2
    :icons md-alerts/+icons+}
   {:name "Material design (Av)"
    :id md-av/+collection-icons-id+
    :type :builtin
    :created-at 3
    :icons md-av/+icons+}
   {:name "Material design (Communication)"
    :id md-comm/+collection-icons-id+
    :type :builtin
    :created-at 4
    :icons md-comm/+icons+}
   {:name "Material design (Content)"
    :id md-content/+collection-icons-id+
    :type :builtin
    :created-at 5
    :icons md-content/+icons+}
   {:name "Material design (Device)"
    :id md-device/+collection-icons-id+
    :type :builtin
    :created-at 6
    :icons md-device/+icons+}
   {:name "Material design (Editor)"
    :id md-editor/+collection-icons-id+
    :type :builtin
    :created-at 7
    :icons md-editor/+icons+}
   {:name "Material design (File)"
    :id md-file/+collection-icons-id+
    :type :builtin
    :created-at 8
    :icons md-file/+icons+}
   {:name "Material design (Hardware)"
    :id md-hardware/+collection-icons-id+
    :type :builtin
    :created-at 9
    :icons md-hardware/+icons+}
   {:name "Material design (Image)"
    :id md-image/+collection-icons-id+
    :type :builtin
    :created-at 10
    :icons md-image/+icons+}
   {:name "Material design (Maps)"
    :id md-maps/+collection-icons-id+
    :type :builtin
    :created-at 11
    :icons md-maps/+icons+}
   {:name "Material design (Navigation)"
    :id md-nav/+collection-icons-id+
    :type :builtin
    :created-at 12
    :icons md-nav/+icons+}
   {:name "Material design (Notifications)"
    :id md-not/+collection-icons-id+
    :type :builtin
    :created-at 13
    :icons md-not/+icons+}
   {:name "Material design (Social)"
    :id md-social/+collection-icons-id+
    :type :builtin
    :created-at 14
    :icons md-social/+icons+}
   {:name "Material design (Toggle)"
    :id md-toggle/+collection-icons-id+
    :type :builtin
    :created-at 15
    :icons md-toggle/+icons+}])

(def collections
  (index-by collections-list :id))
