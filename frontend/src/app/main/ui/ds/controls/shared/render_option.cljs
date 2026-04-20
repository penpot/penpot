;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.render-option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.weak :refer [weak-key]]
   [app.main.ui.ds.controls.shared.option :refer [option*]]
   [app.main.ui.ds.controls.shared.token-option :refer [token-option*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(defn render-option
  [option ref on-click selected focused]
  (let [id   (get option :id)
        name (get option :name)
        type (get option :type)]

    (mf/html
     (case type
       :group
       [:li {:class (stl/css :group-option)
             :role "presentation"
             :key (weak-key option)}
        [:> icon*
         {:icon-id i/arrow-down
          :size "m"
          :class (stl/css :option-check)
          :aria-hidden (when name true)}]
        (d/name name)]

       :separator
       [:hr {:key (weak-key option) :class (stl/css :option-separator)}]

       :empty
       [:li {:key (weak-key option) :class (stl/css :option-empty) :role "presentation"}
        (get option :label)]

       ;; Token option
       :token
       [:> token-option* {:selected (= id selected)
                          :key (weak-key option)
                          :id id
                          :name name
                          :resolved (get option :resolved-value)
                          :ref ref
                          :role "option"
                          :focused (= id focused)
                          :on-click on-click}]

       ;; Normal option
       [:> option* {:selected (= id selected)
                    :key (weak-key option)
                    :id id
                    :label (get option :label)
                    :aria-label (get option :aria-label)
                    :icon (get option :icon)
                    :ref ref
                    :role "option"
                    :focused (= id focused)
                    :dimmed (true? (:dimmed option))
                    :on-click on-click}]))))