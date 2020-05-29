;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.file-uploader
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]))

(mf/defc file-uploader
  [{:keys [accept multi label-text label-class input-id input-ref on-selected] :as props}]
  (let [opt-pick-one #(if multi % (first %))

        on-files-selected (fn [event] (st/emit!
                                        (some-> (dom/get-target event)
                                                (dom/get-files)
                                                (array-seq)
                                                (opt-pick-one)
                                                (on-selected))))]
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

