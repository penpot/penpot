;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.formats
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]))

(defn format-percent
  ([value]
   (format-percent value nil))

  ([value {:keys [precision] :or {precision 2}}]
   (when (d/num? value)
     (let [percent-val (mth/precision (* value 100) precision)]
       (dm/str percent-val "%")))))

(defn format-number
  ([value]
   (format-number value nil))
  ([value {:keys [precision] :or {precision 2}}]
   (when (d/num? value)
     (let [value (mth/precision value precision)]
       (dm/str value)))))

(defn format-pixels
  ([value]
   (format-pixels value nil))

  ([value {:keys [precision] :or {precision 2}}]
   (when (d/num? value)
     (let [value (mth/precision value precision)]
       (dm/str value "px")))))

(defn format-int
  [value]
  (when (d/num? value)
    (let [value (mth/precision value 0)]
      (dm/str value))))
