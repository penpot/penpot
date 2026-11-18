;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.view-menu
  "The control of the compare toolbar that says how the canvas shows the
  changes, and the menu that picks another way or hides them."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.compare :as dwcp]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item* menu-separator*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- on-action
  [action]
  (case action
    "highlight" (st/emit! (dwcp/set-view :highlight))
    "side"      (st/emit! (dwcp/set-view :side))
    "hide"      (st/emit! (dwcp/toggle-hidden))
    nil))

(mf/defc view-item*
  "The label of a view in the menu, ticked when it is the one in use."
  {::mf/private true}
  [{:keys [label is-selected]}]
  [:span {:class (stl/css-case :item true :is-selected is-selected)}
   [:span {:class (stl/css :item-label)} label]
   (when is-selected
     [:> i/icon* {:icon-id i/tick :size "s"}])])

(mf/defc view-mode-menu*
  "`view` is the view in use and `hidden` says the changes are hidden.
  Inside a design review (`is-review`) the control takes the colour of
  the review."
  [{:keys [view hidden is-review]}]
  (let [open*     (mf/use-state false)
        open?     (deref open*)
        on-toggle (mf/use-fn #(swap! open* not))
        on-change (mf/use-fn #(reset! open* %))
        ;; main beside the branch is drawn by the WASM render alone
        wasm?     (features/use-feature "render-wasm/v1")
        highlight (tr "workspace.compare.view.highlight")
        side      (tr "workspace.branches.review.view.side")
        current   (if (and wasm? (= view :side)) side highlight)]
    [:> menu* {:is-open open?
               :on-open-change on-change
               :on-action on-action
               :placement "bottom start"
               :trigger
               (mf/html
                [:button {:class (stl/css-case :trigger true
                                               :is-hidden hidden
                                               :is-review is-review)
                          :aria-label (tr "workspace.compare.view.label")
                          :aria-expanded open?
                          :on-click on-toggle}
                 [:> i/icon* {:icon-id (if hidden i/hide i/shown) :size "s"}]
                 [:> text* {:as "span" :typography t/body-small}
                  (if hidden
                    (tr "workspace.compare.view.hidden")
                    current)]
                 [:> i/icon* {:icon-id i/arrow-down :size "s"}]])}
     [:> menu-item* {:id "highlight" :text-value highlight}
      [:> view-item* {:label highlight
                      :is-selected (and (= view :highlight) (not hidden))}]]
     [:> menu-item* {:id "side" :text-value side :is-disabled (not wasm?)}
      [:> view-item* {:label side
                      :is-selected (and wasm? (= view :side) (not hidden))}]]
     [:> menu-separator* {}]
     [:> menu-item* {:id "hide"}
      (if hidden
        (tr "workspace.compare.view.show")
        (tr "workspace.compare.view.hide"))]]))
