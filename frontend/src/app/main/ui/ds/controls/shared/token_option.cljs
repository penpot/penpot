;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns  app.main.ui.ds.controls.shared.token-option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

;; TODO: Review schema props
(def schema:token-option
  [:map {:title "token option"}
   [:id :string]
   [:resolved [:or
               :int
               :string]]
   [:label :string]])

(mf/defc token-option*
  [{:keys [id label on-click selected ref focused resolved] :rest props}]

  [:> :li {:value id
           :class (stl/css-case :option true
                                :option-with-pill true
                                :option-selected-token selected
                                :option-current focused)
           :aria-selected selected
           :ref ref
           :role "option"
           :id id
           :on-click on-click
           :data-id id
           :data-testid "dropdown-option"}

   (if selected
     [:> icon*
      {:icon-id i/tick
       :size "s"
       :class (stl/css :option-check)
       :aria-hidden (when label true)}]
     [:span {:class (stl/css :icon-placeholder)}])

   [:span {:class (stl/css :option-text)}
    label]

   (when resolved
     [:> :span {:class (stl/css :option-pill)}
      resolved])])