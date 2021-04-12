;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.file-uploader
  (:require
   [rumext.alpha :as mf]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.util.dom :as dom]))

(mf/defc file-uploader
  [{:keys [accept multi label-text label-class input-id input-ref on-selected] :as props}]
  (let [opt-pick-one #(if multi % (first %))

        on-files-selected (fn [event]
                            (let [target (dom/get-target event)]
                              (st/emit!
                                (some-> target
                                        (dom/get-files)
                                        (opt-pick-one)
                                        (on-selected)))
                              (dom/clean-value! target)))]
    [:*
     (when label-text
       [:label {:for input-id :class-name label-class} label-text])

     [:input
      {:style {:display "none"}
       :id input-id
       :multiple multi
       :accept accept
       :type "file"
       :ref input-ref
       :on-change on-files-selected}]]))

