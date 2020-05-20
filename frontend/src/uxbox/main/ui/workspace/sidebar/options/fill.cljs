;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [uxbox.util.object :as obj]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(defn- fill-menu-memo-equals?
  [np op]
  (let [new-shape (obj/get np "shape")
        old-shape (obj/get op "shape")]
    (and (= (:id new-shape)
            (:id old-shape))
         (identical? (:fill-color new-shape)
                     (:fill-color old-shape))
         (identical? (:fill-opacity new-shape)
                     (:fill-opacity old-shape)))))

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % fill-menu-memo-equals?)]}
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)
        color {:value (:fill-color shape)
               :opacity (:fill-opacity shape)}
        handle-change-color (fn [value opacity]
                              (let [change {:fill-color value
                                            :fill-opacity opacity}]
                                (st/emit! (udw/update-shape (:id shape) change))))]
    [:div.element-set
     [:div.element-set-title (t locale "workspace.options.fill")]
     [:div.element-set-content
      [:& color-row {:value color
                     :on-change handle-change-color}]]]))
