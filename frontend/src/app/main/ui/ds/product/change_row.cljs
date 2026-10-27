;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.change-row
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.tag :as tag]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def ^:private schema:change-row
  [:map
   [:class {:optional true} [:maybe :string]]
   [:title :string]
   [:subtitle {:optional true} [:maybe :string]]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? i/icon-list %)]]]]
   [:tone {:optional true} [:maybe (into [:enum] tag/tones)]]
   [:is-selected {:optional true} [:maybe :boolean]]
   [:is-muted {:optional true} [:maybe :boolean]]
   [:on-click {:optional true} [:maybe fn?]]])

(mf/defc change-row*
  "One entry of a change list: a short bar in the tone of its status
  beside a card holding an optional icon, a title over a one-line summary, and a slot at
  each end (`start` for a checkbox or a chevron, `end` for a tag). A
  muted row reads as left out."
  {::mf/schema schema:change-row}
  [{:keys [class title subtitle icon tone is-selected is-muted start end on-click] :rest props}]
  (let [tone        (d/nilv tone "neutral")
        is-selected (d/nilv is-selected false)
        is-muted    (d/nilv is-muted false)
        on-key-down (mf/use-fn
                     (mf/deps on-click)
                     (fn [event]
                       (when (and on-click (or (kbd/enter? event) (kbd/space? event)))
                         (.preventDefault event)
                         (on-click event))))
        props       (mf/spread-props props
                                     {:class [class (stl/css-case :change-row true
                                                                  :is-selected is-selected
                                                                  :is-muted is-muted
                                                                  :tone-accent (= tone "accent")
                                                                  :tone-info (= tone "info")
                                                                  :tone-warning (= tone "warning")
                                                                  :tone-error (= tone "error")
                                                                  :tone-success (= tone "success")
                                                                  :tone-purple (= tone "purple"))]
                                      :role "button"
                                      :tab-index 0
                                      :aria-pressed is-selected
                                      :on-click on-click
                                      :on-key-down on-key-down})]
    [:> :div props
     [:span {:class (stl/css :bar) :aria-hidden true}]
     [:div {:class (stl/css :card)}
      (when start
        [:div {:class (stl/css :start)} start])
      (when icon
        [:> icon* {:icon-id icon :size "s" :class (stl/css :icon)}])
      [:div {:class (stl/css :body)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :title)} title]
       (when subtitle
         [:> text* {:as "span" :typography t/body-small :class (stl/css :subtitle)} subtitle])]
      (when end
        [:div {:class (stl/css :end)} end])]]))
