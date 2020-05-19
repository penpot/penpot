;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.colorpicker
  (:require
   [okulary.core :as l]
   [uxbox.main.store :as st]
   [goog.object :as gobj]
   [rumext.alpha :as mf]
   ["react-color/lib/components/sketch/Sketch" :as sketch]))

(mf/defc colorpicker
  [{:keys [on-change value colors] :as props}]
  (let [local-value (mf/use-state value)
        on-change-complete #(do
                              (reset! local-value %)
                              (on-change (gobj/get % "hex")))]

    [:> sketch/default {:color @local-value
                        :disableAlpha true
                        :presetColors colors
                        :onChangeComplete on-change-complete
                        :style {:box-shadow "none"}}]))

(def most-used-colors
  (letfn [(selector [{:keys [objects]}]
            (as-> {} $
              (reduce (fn [acc shape]
                        (-> acc
                            (update (:fill-color shape) (fnil inc 0))
                            (update (:stroke-color shape) (fnil inc 0))))
                      $ (vals objects))
              (reverse (sort-by second $))
              (map first $)
              (remove nil? $)))]
    (l/derived selector st/state)))
