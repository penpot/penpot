;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.file-uploader
  (:require
   [app.main.store :as st]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc file-uploader
  {::mf/forward-ref true}
  [{:keys [accept multi label-text label-class input-id on-selected data-testid] :as props} input-ref]
  (let [opt-pick-one #(if multi % (first %))

        on-files-selected
        (mf/use-callback
         (mf/deps opt-pick-one)
         (fn [event]
           (let [target (dom/get-target event)]
             (st/emit!
              (some-> target
                      (dom/get-files)
                      (opt-pick-one)
                      (on-selected)))
             (dom/clean-value! target))))]
    [:*
     (when label-text
       [:label {:for input-id :class-name label-class} label-text])

     [:input {:style {:display "none"
                      :width 0}
              :id input-id
              :multiple multi
              :accept accept
              :type "file"
              :ref input-ref
              :on-change on-files-selected
              :data-testid data-testid
              :aria-label "uploader"}]]))

