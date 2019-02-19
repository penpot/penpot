;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.builtins.colors
  (:require [uxbox.util.uuid :as uuid]
            [uxbox.util.data :refer (index-by)]))

(def collections-list
  [{:name "Grays"
    :id #uuid "00000000-0000-0000-0000-000000000001"
    :created-at 1
    :type :builtin
    :colors #{"#D3D3D3"
              "#C0C0C0"
              "#A9A9A9"
              "#DCDCDC"
              "#808080"
              "#696969"
              "#000000"}}

   {:name "Silver"
    :id #uuid "00000000-0000-0000-0000-000000000002"
    :type :builtin
    :created-at 2
    :colors #{"#D3D3D3"
              "#C0C0C0"
              "#A9A9A9"
              "#808080"}}

   {:name "Blues"
    :id #uuid "00000000-0000-0000-0000-000000000003"
    :type :builtin
    :created-at 3
    :colors #{"#F0F8FF"
              "#E6E6FA"
              "#B0E0E6"
              "#ADD8E6"
              "#87CEFA"
              "#87CEEB"
              "#00BFFF"
              "#B0C4DE"
              "#1E90FF"
              "#6495ED"
              "#4682B4"
              "#5F9EA0"
              "#7B68EE"
              "#6A5ACD"
              "#483D8B"
              "#4169E1"
              "#0000FF"
              "#0000CD"
              "#00008B"
              "#000080"
              "#191970"
              "#8A2BE2"
              "#4B0082"}}

   {:name "UXBOX"
    :id #uuid "00000000-0000-0000-0000-000000000004"
    :type :builtin
    :created-at 4
    :colors #{"#78dbbe"
              "#b6dd75"
              "#a599c6"
              "#e6a16f"
              "#de4762"
              "#59b9e2"
              "#ffffff"
              "#000000"
              "#90969d"
              "#2C2C2C"
              "#3d3f40"
              "#181818"
              "#a9adaf"
              "#808386"
              "#4a4e52"
              "#e0e6e9"
              "#8d9496"
              "#4e4f50"
              "#878c8e"}}

   {:name "Common"
    :id #uuid "00000000-0000-0000-0000-000000000005"
    :type :builtin
    :created-at 5
    :colors #{"#007bff"
              "#6610f2"
              "#6f42c1"
              "#e83e8c"
              "#dc3545"
              "#fd7e14"
              "#ffc107"
              "#28a745"
              "#20c997"
              "#17a2b8"}}

   {:name "Social"
    :id #uuid "00000000-0000-0000-0000-000000000006"
    :type :builtin
    :created-at 6
    :colors #{"#365899"
              "#1b95e0"
              "#01e675"}}])

(def collections
  (index-by collections-list :id))
