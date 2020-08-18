;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.ui.colorpicker
  (:require
   [okulary.core :as l]
   [app.main.store :as st]
   [goog.object :as gobj]
   [rumext.alpha :as mf]
   [app.util.color :refer [hex->rgb]]
   ["react-color/lib/components/chrome/Chrome" :as pickerskin]))

(mf/defc colorpicker
  [{:keys [on-change value opacity colors disable-opacity] :as props}]
  (let [hex-value (mf/use-state (or value "#FFFFFF"))
        alpha-value (mf/use-state (or opacity 1))
        [r g b] (hex->rgb @hex-value)
        on-change-complete #(let [hex (gobj/get % "hex")
                                  opacity (-> % (gobj/get "rgb") (gobj/get "a"))]
                              (reset! hex-value hex)
                              (reset! alpha-value opacity)
                              (on-change hex opacity))]

    [:> pickerskin/default {:color #js { :r r :g g :b b :a @alpha-value}
                            :presetColors colors
                            :onChange on-change-complete
                            :disableAlpha disable-opacity
                            :styles {:default {:picker {:padding "10px"}}}}]))

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
